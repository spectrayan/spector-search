import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FormsModule } from '@angular/forms';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { ThemeService } from '../../core/services/theme.service';
import { PROFILE_PARAMS, CognitiveProfile } from '../../core/models/memory-types';
import * as THREE from 'three';

const MAX_NODES = 200;
const TIER_COLORS: Record<string, number> = {
  WORKING: 0xffb74d,
  EPISODIC: 0x66bb6a,
  SEMANTIC: 0x42a5f5,
  PROCEDURAL: 0xab47bc,
};

const EDGE_COLORS = {
  hebbian: 0xffffff,
  temporal: 0x00bcd4,
  entity: 0xffc107,
};

interface GraphNode {
  position: THREE.Vector3;
  velocity: THREE.Vector3;
  tier: string;
  activation: number;
  targetActivation: number;
  mesh: THREE.Mesh;
  glowMesh: THREE.Mesh;
  label: string;
}

interface GraphEdge {
  line: THREE.Line;
  type: 'hebbian' | 'temporal' | 'entity';
  from: number;
  to: number;
  activation: number;
}

interface Particle {
  mesh: THREE.Mesh;
  trailMesh: THREE.Mesh | null;
  edgeIndex: number;
  progress: number;
  speed: number;
  alive: boolean;
  color: number;
}

@Component({
  selector: 'cortex-neural-graph',
  imports: [MatCheckboxModule, MatTooltipModule, FormsModule],
  templateUrl: './neural-graph.component.html',
  styleUrl: './neural-graph.component.scss',
})
export class NeuralGraphComponent implements AfterViewInit, OnDestroy {

  @ViewChild('canvasContainer', { static: true })
  private canvasContainer!: ElementRef<HTMLDivElement>;

  protected readonly state = inject(CortexStateService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  private scene!: THREE.Scene;
  private camera!: THREE.PerspectiveCamera;
  private renderer!: THREE.WebGLRenderer;
  private nodes: GraphNode[] = [];
  private graphEdges: GraphEdge[] = [];
  private particles: Particle[] = [];
  private queryTrailMesh: THREE.Mesh | null = null;
  private queryTrailTarget: THREE.Vector3 | null = null;
  private ambientParticleTimer = 0;
  private animationId = 0;
  private mouseX = 0;
  private mouseY = 0;
  private clock = new THREE.Clock();

  // Profile color tint
  private profileHue = 0;

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.initScene();
    this.generateNodes();
    this.generateEdges();
    this.createQueryTrail();
    this.animate();

    // React to query traces — pulse nodes + launch traversal particle
    effect(() => {
      const trace = this.state.currentQueryTrace();
      if (trace) {
        this.pulseRandomNodes(trace.finalTopK + trace.hebbianActivated);
        this.launchTraversalParticles(trace.hebbianActivated + trace.temporalLinked + trace.entityDiscovered);
      }
    });

    // React to graph pulses — pulse edges by type
    effect(() => {
      const pulses = this.state.graphPulses();
      if (pulses.length > 0) {
        const latest = pulses[0];
        this.pulseEdgesByType(latest.graphType);
      }
    });

    // React to reflect cycles — dim all edges (consolidation)
    effect(() => {
      const reflect = this.state.lastReflect();
      if (reflect) {
        this.consolidationAnimation(reflect.hebbianEdgesRemoved);
      }
    });

    // React to profile changes — shift color tint
    effect(() => {
      const profile = this.state.activeProfile();
      this.applyProfileVisuals(profile);
    });
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.animationId);
    this.renderer?.dispose();
  }

  onMouseMove(event: MouseEvent): void {
    const rect = this.canvasContainer.nativeElement.getBoundingClientRect();
    this.mouseX = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.mouseY = -((event.clientY - rect.top) / rect.height) * 2 + 1;
  }

  private initScene(): void {
    const container = this.canvasContainer.nativeElement;
    const width = container.clientWidth;
    const height = container.clientHeight;

    this.scene = new THREE.Scene();

    this.camera = new THREE.PerspectiveCamera(60, width / height, 0.1, 1000);
    this.camera.position.z = 80;

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setClearColor(0x000000, 0);
    // Enable tone mapping for bloom-like glow
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.2;
    container.appendChild(this.renderer.domElement);

    this.scene.add(new THREE.AmbientLight(0xffffff, 0.4));
    const pointLight = new THREE.PointLight(0xffffff, 0.8, 200);
    pointLight.position.copy(this.camera.position);
    this.scene.add(pointLight);

    const observer = new ResizeObserver(() => {
      const w = container.clientWidth;
      const h = container.clientHeight;
      this.camera.aspect = w / h;
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(w, h);
    });
    observer.observe(container);
  }

  private generateNodes(): void {
    const tiers = Object.keys(TIER_COLORS);
    const tierWeights = [0.05, 0.35, 0.45, 0.15];

    for (let i = 0; i < MAX_NODES; i++) {
      let rand = Math.random();
      let tierIdx = 0;
      for (let t = 0; t < tierWeights.length; t++) {
        rand -= tierWeights[t];
        if (rand <= 0) { tierIdx = t; break; }
      }
      const tier = tiers[tierIdx];
      const color = TIER_COLORS[tier];

      const radius = 15 + tierIdx * 12 + Math.random() * 8;
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);
      const pos = new THREE.Vector3(
        radius * Math.sin(phi) * Math.cos(theta),
        radius * Math.sin(phi) * Math.sin(theta),
        radius * Math.cos(phi),
      );

      const geometry = new THREE.SphereGeometry(0.4 + Math.random() * 0.3, 12, 12);
      const material = new THREE.MeshPhongMaterial({
        color, emissive: color, emissiveIntensity: 0.1,
        transparent: true, opacity: 0.85,
      });
      const mesh = new THREE.Mesh(geometry, material);
      mesh.position.copy(pos);
      this.scene.add(mesh);

      const glowGeometry = new THREE.SphereGeometry(1.5, 8, 8);
      const glowMaterial = new THREE.MeshBasicMaterial({
        color, transparent: true, opacity: 0,
      });
      const glowMesh = new THREE.Mesh(glowGeometry, glowMaterial);
      glowMesh.position.copy(pos);
      this.scene.add(glowMesh);

      this.nodes.push({
        position: pos, velocity: new THREE.Vector3(
          (Math.random() - 0.5) * 0.015, (Math.random() - 0.5) * 0.015, (Math.random() - 0.5) * 0.015,
        ),
        tier, activation: 0, targetActivation: 0, mesh, glowMesh,
        label: `${tier.toLowerCase()}-${i}`,
      });
    }
  }

  private generateEdges(): void {
    const types: Array<'hebbian' | 'temporal' | 'entity'> = ['hebbian', 'temporal', 'entity'];

    for (let i = 0; i < this.nodes.length; i++) {
      let connections = 0;
      for (let j = i + 1; j < this.nodes.length && connections < 3; j++) {
        const dist = this.nodes[i].position.distanceTo(this.nodes[j].position);
        if (dist < 20 && Math.random() > 0.55) {
          const type = types[Math.floor(Math.random() * types.length)];
          const color = EDGE_COLORS[type];

          const material = new THREE.LineBasicMaterial({
            color, transparent: true, opacity: type === 'hebbian' ? 0.08 : 0.06,
          });

          // For temporal edges, use dashed lines
          let line: THREE.Line;
          if (type === 'temporal') {
            const dashMat = new THREE.LineDashedMaterial({
              color, transparent: true, opacity: 0.06,
              dashSize: 1, gapSize: 0.5,
            });
            const geometry = new THREE.BufferGeometry().setFromPoints([
              this.nodes[i].position, this.nodes[j].position,
            ]);
            line = new THREE.Line(geometry, dashMat);
            line.computeLineDistances();
          } else {
            const geometry = new THREE.BufferGeometry().setFromPoints([
              this.nodes[i].position, this.nodes[j].position,
            ]);
            line = new THREE.Line(geometry, material);
          }

          this.scene.add(line);
          this.graphEdges.push({ line, type, from: i, to: j, activation: 0 });
          connections++;
        }
      }
    }
  }

  private createQueryTrail(): void {
    const geometry = new THREE.SphereGeometry(1.8, 20, 20);
    const material = new THREE.MeshBasicMaterial({
      color: 0xbb86fc, transparent: true, opacity: 0,
    });
    this.queryTrailMesh = new THREE.Mesh(geometry, material);
    this.scene.add(this.queryTrailMesh);
  }

  private pulseRandomNodes(count: number): void {
    for (const node of this.nodes) {
      node.targetActivation *= 0.3;
    }
    const indices = new Set<number>();
    while (indices.size < Math.min(count, this.nodes.length)) {
      indices.add(Math.floor(Math.random() * this.nodes.length));
    }
    for (const idx of indices) {
      this.nodes[idx].targetActivation = 0.7 + Math.random() * 0.3;
    }

    // Activate query trail at the first activated node
    if (this.queryTrailMesh && indices.size > 0) {
      const firstIdx = indices.values().next().value;
      if (firstIdx !== undefined) {
        this.queryTrailTarget = this.nodes[firstIdx].position.clone();
        const mat = this.queryTrailMesh.material as THREE.MeshBasicMaterial;
        mat.opacity = 0.9;
        this.queryTrailMesh.scale.setScalar(1);
        this.queryTrailMesh.position.copy(this.queryTrailTarget);
      }
    }
  }

  private launchTraversalParticles(count: number): void {
    // Launch a burst of 20-40 particles across random edges
    const burstSize = Math.max(20, count * 5);
    for (let i = 0; i < burstSize; i++) {
      this.spawnParticle(Math.random() * 0.3); // stagger start progress
    }
  }

  private spawnParticle(startProgress = 0): void {
    if (this.graphEdges.length === 0) return;
    const edgeIndex = Math.floor(Math.random() * this.graphEdges.length);
    const edge = this.graphEdges[edgeIndex];
    const color = EDGE_COLORS[edge.type];

    // Main particle sphere — larger and brighter
    const geometry = new THREE.SphereGeometry(0.8, 8, 8);
    const material = new THREE.MeshBasicMaterial({
      color, transparent: true, opacity: 0.95,
    });
    const mesh = new THREE.Mesh(geometry, material);
    this.scene.add(mesh);

    // Trail glow (larger, dimmer sphere behind)
    const trailGeometry = new THREE.SphereGeometry(2.0, 6, 6);
    const trailMaterial = new THREE.MeshBasicMaterial({
      color, transparent: true, opacity: 0.25,
    });
    const trailMesh = new THREE.Mesh(trailGeometry, trailMaterial);
    this.scene.add(trailMesh);

    this.particles.push({
      mesh, trailMesh, edgeIndex, progress: startProgress,
      speed: 0.02 + Math.random() * 0.03, alive: true, color,
    });
  }

  private pulseEdgesByType(type: 'hebbian' | 'temporal' | 'entity'): void {
    const layers = this.state.graphLayers();
    if ((type === 'hebbian' && !layers.hebbian) ||
        (type === 'temporal' && !layers.temporal) ||
        (type === 'entity' && !layers.entity)) return;

    for (const edge of this.graphEdges) {
      if (edge.type === type) {
        edge.activation = 0.8;
      }
    }
  }

  private consolidationAnimation(removedCount: number): void {
    // Dim random edges to simulate pruning
    const toRemove = Math.min(removedCount, Math.floor(this.graphEdges.length * 0.05));
    for (let i = 0; i < toRemove; i++) {
      const edge = this.graphEdges[Math.floor(Math.random() * this.graphEdges.length)];
      const mat = edge.line.material as THREE.LineBasicMaterial;
      mat.opacity = 0.01; // Nearly invisible — "pruned"
    }
  }

  private applyProfileVisuals(profile: CognitiveProfile): void {
    const params = PROFILE_PARAMS[profile];

    if (profile === CognitiveProfile.HYPERFOCUS) {
      // Tunnel vision: suppress all but high-activation nodes
      for (const node of this.nodes) {
        const mat = node.mesh.material as THREE.MeshPhongMaterial;
        mat.opacity = node.activation > 0.3 ? 0.95 : 0.15;
      }
    } else if (profile === CognitiveProfile.PARANOID_SENTINEL) {
      // Red shift
      for (const node of this.nodes) {
        const mat = node.mesh.material as THREE.MeshPhongMaterial;
        mat.emissive.setHex(0xff4444);
      }
    } else if (profile === CognitiveProfile.DIVERGENT) {
      // Rainbow shimmer — handled in animate loop via hue shift
      this.profileHue = 0;
    } else {
      // Reset to normal
      for (const node of this.nodes) {
        const mat = node.mesh.material as THREE.MeshPhongMaterial;
        mat.emissive.setHex(TIER_COLORS[node.tier]);
        mat.opacity = 0.85;
      }
    }
  }

  private animate(): void {
    this.animationId = requestAnimationFrame(() => this.animate());
    const delta = this.clock.getDelta();
    const time = this.clock.getElapsedTime();
    const layers = this.state.graphLayers();

    // Camera orbit
    this.camera.position.x = 80 * Math.sin(time * 0.04) + this.mouseX * 15;
    this.camera.position.y = 25 * Math.sin(time * 0.025) + this.mouseY * 15;
    this.camera.lookAt(0, 0, 0);

    // Divergent rainbow hue shift
    const isDivergent = this.state.activeProfile() === CognitiveProfile.DIVERGENT;
    if (isDivergent) this.profileHue = (this.profileHue + delta * 30) % 360;

    // Animate nodes
    for (const node of this.nodes) {
      node.position.add(node.velocity);
      node.mesh.position.copy(node.position);
      node.glowMesh.position.copy(node.position);

      if (node.position.length() > 55) {
        node.velocity.multiplyScalar(-1);
      }

      node.activation += (node.targetActivation - node.activation) * delta * 3;
      node.targetActivation *= 0.995;

      const mat = node.mesh.material as THREE.MeshPhongMaterial;
      mat.emissiveIntensity = 0.1 + node.activation * 1.2;

      if (isDivergent && node.activation > 0.2) {
        const hue = (this.profileHue + node.position.x * 3) % 360;
        mat.emissive.setHSL(hue / 360, 0.8, 0.5);
      }

      const glowMat = node.glowMesh.material as THREE.MeshBasicMaterial;
      glowMat.opacity = node.activation * 0.35;
      node.glowMesh.scale.setScalar(1 + node.activation * 2.5);
    }

    // Animate edges with layer visibility
    for (const edge of this.graphEdges) {
      const visible = (edge.type === 'hebbian' && layers.hebbian) ||
                      (edge.type === 'temporal' && layers.temporal) ||
                      (edge.type === 'entity' && layers.entity);

      edge.line.visible = visible;

      if (visible) {
        const mat = edge.line.material as THREE.LineBasicMaterial;
        const baseOpacity = edge.type === 'hebbian' ? 0.08 : 0.06;

        if (edge.activation > 0) {
          mat.opacity = baseOpacity + edge.activation * 0.5;
          edge.activation *= 0.96;
        } else if (mat.opacity > baseOpacity) {
          mat.opacity *= 0.98;
        }

        // Update edge geometry to follow node positions
        const positions = edge.line.geometry.attributes['position'] as THREE.BufferAttribute;
        const fromNode = this.nodes[edge.from];
        const toNode = this.nodes[edge.to];
        positions.setXYZ(0, fromNode.position.x, fromNode.position.y, fromNode.position.z);
        positions.setXYZ(1, toNode.position.x, toNode.position.y, toNode.position.z);
        positions.needsUpdate = true;
      }
    }

    // Ambient continuous particle stream — spawn 1-2 particles every few frames
    if (layers.particles) {
      this.ambientParticleTimer += delta;
      if (this.ambientParticleTimer > 0.15 && this.particles.length < 60) {
        this.ambientParticleTimer = 0;
        this.spawnParticle();
      }
    }

    // Animate particles along edges
    if (layers.particles) {
      for (const particle of this.particles) {
        if (!particle.alive) continue;

        particle.progress += particle.speed;
        if (particle.progress >= 1) {
          particle.alive = false;
          this.scene.remove(particle.mesh);
          if (particle.trailMesh) this.scene.remove(particle.trailMesh);
          continue;
        }

        const edge = this.graphEdges[particle.edgeIndex];
        const fromNode = this.nodes[edge.from];
        const toNode = this.nodes[edge.to];
        const pos = new THREE.Vector3();
        pos.lerpVectors(fromNode.position, toNode.position, particle.progress);
        particle.mesh.position.copy(pos);

        // Trail follows slightly behind
        if (particle.trailMesh) {
          const trailProgress = Math.max(0, particle.progress - 0.08);
          particle.trailMesh.position.lerpVectors(fromNode.position, toNode.position, trailProgress);
          const trailMat = particle.trailMesh.material as THREE.MeshBasicMaterial;
          trailMat.opacity = Math.sin(particle.progress * Math.PI) * 0.25;
        }

        // Particle fades in/out along its path
        const mat = particle.mesh.material as THREE.MeshBasicMaterial;
        mat.opacity = Math.sin(particle.progress * Math.PI) * 0.95;
        // Pulse scale slightly
        particle.mesh.scale.setScalar(0.8 + Math.sin(particle.progress * Math.PI) * 0.5);
      }

      // Clean up dead particles
      this.particles = this.particles.filter(p => p.alive);
    }

    // Query trail expanding glow ring
    if (this.queryTrailMesh) {
      const mat = this.queryTrailMesh.material as THREE.MeshBasicMaterial;
      if (mat.opacity > 0.01) {
        mat.opacity *= 0.96;
        const scale = 1 + (1 - mat.opacity) * 5;
        this.queryTrailMesh.scale.setScalar(scale);
      }
    }

    this.renderer.render(this.scene, this.camera);
  }
}
