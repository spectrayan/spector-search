import { Component, inject, signal, ElementRef, ViewChild, AfterViewInit, OnDestroy, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSliderModule } from '@angular/material/slider';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { FormsModule } from '@angular/forms';
import { MemoryTableService, GraphNode, GraphEdge } from '../../core/services/memory-table.service';
import * as THREE from 'three';

const TIER_COLORS: Record<string, number> = {
  WORKING: 0xffb74d,
  EPISODIC: 0x66bb6a,
  SEMANTIC: 0x42a5f5,
  PROCEDURAL: 0xab47bc,
};

const EDGE_TYPE_COLORS: Record<string, number> = {
  HEBBIAN: 0xffffff,
  TEMPORAL: 0x00bcd4,
  ENTITY: 0xffc107,
};

const STORAGE_KEY = 'spector.graph.camera';

interface CameraState {
  theta: number;
  phi: number;
  radius: number;
}

interface ExplorerNode {
  id: string;
  tier: string;
  text: string;
  importance: number;
  position: THREE.Vector3;
  velocity: THREE.Vector3;
  mesh: THREE.Sprite;      // star core sprite
  glowMesh: THREE.Sprite;  // outer glow halo sprite
  labelSprite: THREE.Sprite;
  selected: boolean;
  baseSize: number;        // for pulsing animation
}

interface ExplorerEdge {
  from: string;
  to: string;
  type: string;
  weight: number;
  relation: string | null;
  line: THREE.Line;
  labelSprite?: THREE.Sprite;
  weightSprite?: THREE.Sprite;
}

interface HoverInfo {
  x: number;
  y: number;
  id: string;
  tier: string;
  text: string;
  importance: number;
}

@Component({
  selector: 'cortex-graph-explorer',
  standalone: true,
  imports: [
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSliderModule,
    MatChipsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    FormsModule,
  ],
  templateUrl: './graph-explorer.component.html',
  styleUrl: './graph-explorer.component.scss',
})
export class GraphExplorerComponent implements AfterViewInit, OnDestroy {
  @ViewChild('graphCanvas', { static: true })
  private canvasContainer!: ElementRef<HTMLDivElement>;

  private readonly platformId = inject(PLATFORM_ID);
  private readonly memoryService = inject(MemoryTableService);

  private scene!: THREE.Scene;
  private camera!: THREE.PerspectiveCamera;
  private renderer!: THREE.WebGLRenderer;
  private animationId = 0;
  private nodes: ExplorerNode[] = [];
  private edges: ExplorerEdge[] = [];
  private raycaster = new THREE.Raycaster();
  private mouse = new THREE.Vector2();
  private gridGroup!: THREE.Group;
  private dustParticles!: THREE.Points;

  // Orbit state
  private isDragging = false;
  private orbitTheta = 0;
  private orbitPhi = Math.PI / 4;
  private orbitRadius = 100;
  private dragStartX = 0;
  private dragStartY = 0;
  private dragStartTheta = 0;
  private dragStartPhi = 0;
  private timer = new THREE.Timer();
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  // Fly-to animation state
  private lookAtTarget = new THREE.Vector3(0, 0, 0);
  private flyFromPos = new THREE.Vector3(0, 0, 0);
  private flyToPos: THREE.Vector3 | null = null;
  private flyFromRadius = 100;
  private flyToRadius = 0;
  private flyProgress = 1; // 1 = done

  readonly selectedNode = signal<ExplorerNode | null>(null);
  readonly selectedEdge = signal<ExplorerEdge | null>(null);
  readonly hoverInfo = signal<HoverInfo | null>(null);
  readonly searchQuery = signal('');
  readonly depth = signal(2);
  readonly showHebbian = signal(true);
  readonly showTemporal = signal(true);
  readonly showEntity = signal(true);
  readonly showLabels = signal(true);
  readonly nodeCount = signal(0);
  readonly edgeCount = signal(0);
  readonly graphLoading = signal(false);
  readonly graphError = signal<string | null>(null);

  // Stats for the HUD
  readonly avgImportance = signal(0);
  readonly densityRatio = signal(0);
  readonly hebbianCount = signal(0);
  readonly temporalCount = signal(0);
  readonly entityCount = signal(0);

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.restoreCameraState();
    this.initScene();
    this.loadGraphData();
    this.animate();
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.animationId);
    this.renderer?.dispose();
    if (this.saveTimer) clearTimeout(this.saveTimer);
  }

  onMouseMove(event: MouseEvent): void {
    if (this.isDragging) {
      const dx = (event.clientX - this.dragStartX) * 0.005;
      const dy = (event.clientY - this.dragStartY) * 0.005;
      this.orbitTheta = this.dragStartTheta - dx;
      this.orbitPhi = Math.max(0.1, Math.min(Math.PI - 0.1, this.dragStartPhi + dy));
      return;
    }

    // Hover detection via screen-space projection
    const container = this.canvasContainer.nativeElement;
    const rect = container.getBoundingClientRect();
    this.mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

    let closestNode: ExplorerNode | null = null;
    let closestDist = 0.04; // NDC threshold for hover
    for (const node of this.nodes) {
      const projected = node.position.clone().project(this.camera);
      const dx = projected.x - this.mouse.x;
      const dy = projected.y - this.mouse.y;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist < closestDist) {
        closestDist = dist;
        closestNode = node;
      }
    }

    if (closestNode) {
      this.hoverInfo.set({
        x: event.clientX - rect.left,
        y: event.clientY - rect.top,
        id: closestNode.id,
        tier: closestNode.tier,
        text: closestNode.text,
        importance: closestNode.importance,
      });
      container.style.cursor = 'pointer';
      return;
    }
    this.hoverInfo.set(null);
    container.style.cursor = this.isDragging ? 'grabbing' : 'grab';
  }

  onMouseDown(event: MouseEvent): void {
    this.isDragging = true;
    this.dragStartX = event.clientX;
    this.dragStartY = event.clientY;
    this.dragStartTheta = this.orbitTheta;
    this.dragStartPhi = this.orbitPhi;
    this.hoverInfo.set(null);
    event.preventDefault();
  }

  onMouseUp(event: MouseEvent): void {
    if (this.isDragging) {
      const dx = event.clientX - this.dragStartX;
      const dy = event.clientY - this.dragStartY;
      const moved = Math.sqrt(dx * dx + dy * dy);
      this.isDragging = false;
      this.debounceSaveCameraState();

      // If mouse barely moved, treat as a click
      if (moved < 5) {
        this.handleClick(event);
      }
    }
  }

  onWheel(event: WheelEvent): void {
    this.orbitRadius = Math.max(10, Math.min(800, this.orbitRadius + event.deltaY * 0.15));
    this.debounceSaveCameraState();
    event.preventDefault();
  }

  onClick(event: MouseEvent): void {
    // Handled by onMouseUp click detection
  }

  private handleClick(event: MouseEvent): void {
    const container = this.canvasContainer.nativeElement;
    const rect = container.getBoundingClientRect();
    this.mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

    this.raycaster.setFromCamera(this.mouse, this.camera);

    // Find closest node via screen-space projection (more reliable than raycaster for sprites)
    let closestNode: ExplorerNode | null = null;
    let closestNodeDist = 0.06; // screen-space threshold in NDC
    for (const node of this.nodes) {
      const projected = node.position.clone().project(this.camera);
      const dx = projected.x - this.mouse.x;
      const dy = projected.y - this.mouse.y;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist < closestNodeDist) {
        closestNodeDist = dist;
        closestNode = node;
      }
    }
    if (closestNode) {
      this.selectedNode.set(closestNode);
      this.selectedEdge.set(null);
      this.flyToNode(closestNode);
      return;
    }

    // Check edge intersections
    let closestEdge: ExplorerEdge | null = null;
    let closestDist = 3.0;
    for (const edge of this.edges) {
      if (!edge.line.visible) continue;
      const fromNode = this.nodes.find(n => n.id === edge.from);
      const toNode = this.nodes.find(n => n.id === edge.to);
      if (!fromNode || !toNode) continue;
      const midpoint = new THREE.Vector3().addVectors(fromNode.position, toNode.position).multiplyScalar(0.5);
      const projected = midpoint.clone().project(this.camera);
      const dx = projected.x - this.mouse.x;
      const dy = projected.y - this.mouse.y;
      const screenDist = Math.sqrt(dx * dx + dy * dy);
      if (screenDist < 0.08 && screenDist < closestDist) {
        closestDist = screenDist;
        closestEdge = edge;
      }
    }
    if (closestEdge) {
      this.selectedEdge.set(closestEdge);
      this.selectedNode.set(null);
      this.highlightEdge(closestEdge);
      return;
    }

    this.selectedNode.set(null);
    this.selectedEdge.set(null);
    this.clearEdgeHighlight();
  }

  refreshGraph(): void {
    this.clearScene();
    this.lookAtTarget.set(0, 0, 0);
    this.flyToPos = null;
    this.flyProgress = 1;
    this.loadGraphData();
  }

  /** Smoothly fly the camera to orbit around a specific node */
  private flyToNode(node: ExplorerNode): void {
    this.flyFromPos.copy(this.lookAtTarget);
    this.flyFromRadius = this.orbitRadius;
    this.flyToPos = node.position.clone();
    this.flyToRadius = Math.max(20, node.baseSize * 15);
    this.flyProgress = 0;
  }

  /** Double-click: reset camera to full graph overview */
  onDoubleClick(): void {
    this.flyFromPos.copy(this.lookAtTarget);
    this.flyFromRadius = this.orbitRadius;
    this.flyToPos = new THREE.Vector3(0, 0, 0);
    this.flyToRadius = 120;
    this.flyProgress = 0;
    this.selectedNode.set(null);
    this.selectedEdge.set(null);
    this.clearEdgeHighlight();
  }

  private easeInOutCubic(t: number): number {
    return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  }

  edgeLabel(edge: ExplorerEdge): string {
    switch (edge.type) {
      case 'HEBBIAN': return `weight: ${edge.weight.toFixed(1)}`;
      case 'TEMPORAL': return '→ next';
      case 'ENTITY': return edge.relation ?? 'RELATED';
      default: return edge.type;
    }
  }

  edgeIcon(type: string): string {
    switch (type) {
      case 'HEBBIAN': return 'link';
      case 'TEMPORAL': return 'timeline';
      case 'ENTITY': return 'category';
      default: return 'device_hub';
    }
  }

  // ── Camera persistence ──────────────────────────────────

  private saveCameraState(): void {
    const state: CameraState = {
      theta: this.orbitTheta,
      phi: this.orbitPhi,
      radius: this.orbitRadius,
    };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch { /* storage unavailable */ }
  }

  private restoreCameraState(): void {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const state: CameraState = JSON.parse(raw);
        this.orbitTheta = state.theta ?? 0;
        this.orbitPhi = state.phi ?? Math.PI / 4;
        this.orbitRadius = state.radius ?? 100;
      }
    } catch { /* ignore */ }
  }

  private debounceSaveCameraState(): void {
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.saveCameraState(), 300);
  }

  // ── Highlighting ────────────────────────────────────────

  private highlightEdge(edge: ExplorerEdge): void {
    for (const e of this.edges) {
      const mat = e.line.material as THREE.LineBasicMaterial;
      mat.opacity = e === edge ? 0.8 : 0.1;
      mat.linewidth = e === edge ? 3 : 1;
    }
  }

  private clearEdgeHighlight(): void {
    for (const e of this.edges) {
      const mat = e.line.material as THREE.LineBasicMaterial;
      mat.opacity = e.type === 'TEMPORAL' ? 0.35 : 0.3;
      mat.linewidth = 1;
    }
  }

  // ── Scene setup ─────────────────────────────────────────

  private initScene(): void {
    const container = this.canvasContainer.nativeElement;
    const width = container.clientWidth;
    const height = container.clientHeight;

    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(60, width / height, 0.1, 2000);
    this.camera.position.z = this.orbitRadius;

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setClearColor(0x000000, 0);
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.2;
    container.appendChild(this.renderer.domElement);

    this.scene.add(new THREE.AmbientLight(0xffffff, 0.4));
    const pointLight = new THREE.PointLight(0xffffff, 0.8, 200);
    pointLight.position.copy(this.camera.position);
    this.scene.add(pointLight);

    // Coordinate grid shell (subtle wireframe sphere)
    this.gridGroup = new THREE.Group();
    this.createCoordinateGrid();
    this.scene.add(this.gridGroup);

    // Cosmic dust particles
    this.createDustField();

    const observer = new ResizeObserver(() => {
      const w = container.clientWidth;
      const h = container.clientHeight;
      this.camera.aspect = w / h;
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(w, h);
    });
    observer.observe(container);
  }

  /** Coordinate grid — thin wireframe rings like a star chart */
  private createCoordinateGrid(): void {
    const gridMat = new THREE.LineBasicMaterial({ color: 0x4488ff, transparent: true, opacity: 0.04 });

    // Equatorial and meridian circles
    for (let i = 0; i < 3; i++) {
      const curve = new THREE.EllipseCurve(0, 0, 45, 45, 0, Math.PI * 2, false, 0);
      const pts = curve.getPoints(96);
      const geo = new THREE.BufferGeometry().setFromPoints(pts.map(p => {
        if (i === 0) return new THREE.Vector3(p.x, 0, p.y);
        if (i === 1) return new THREE.Vector3(p.x, p.y, 0);
        return new THREE.Vector3(0, p.x, p.y);
      }));
      this.gridGroup.add(new THREE.Line(geo, gridMat));
    }

    // Latitude rings
    for (const r of [15, 30]) {
      const curve = new THREE.EllipseCurve(0, 0, r, r, 0, Math.PI * 2, false, 0);
      const pts = curve.getPoints(64);
      const geo = new THREE.BufferGeometry().setFromPoints(pts.map(p => new THREE.Vector3(p.x, 0, p.y)));
      this.gridGroup.add(new THREE.Line(geo, new THREE.LineBasicMaterial({ color: 0x4488ff, transparent: true, opacity: 0.025 })));
    }
  }

  /** Background dust particles for depth and ambiance */
  private createDustField(): void {
    const count = 600;
    const positions = new Float32Array(count * 3);
    const colors = new Float32Array(count * 3);
    const sizes = new Float32Array(count);

    for (let i = 0; i < count; i++) {
      const r = 50 + Math.random() * 100;
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);
      positions[i * 3] = r * Math.sin(phi) * Math.cos(theta);
      positions[i * 3 + 1] = r * Math.sin(phi) * Math.sin(theta);
      positions[i * 3 + 2] = r * Math.cos(phi);

      const brightness = 0.3 + Math.random() * 0.5;
      colors[i * 3] = brightness * 0.6;
      colors[i * 3 + 1] = brightness * 0.7;
      colors[i * 3 + 2] = brightness;

      sizes[i] = 0.08 + Math.random() * 0.15;
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    geo.setAttribute('size', new THREE.BufferAttribute(sizes, 1));

    const mat = new THREE.PointsMaterial({
      size: 0.12,
      vertexColors: true,
      transparent: true,
      opacity: 0.4,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });

    this.dustParticles = new THREE.Points(geo, mat);
    this.scene.add(this.dustParticles);
  }

  // ── Graph data loading ──────────────────────────────────

  private loadGraphData(): void {
    this.graphLoading.set(true);
    this.graphError.set(null);

    this.memoryService.getGraphOverview(120).subscribe({
      next: (response) => {
        this.buildGraphFromResponse(response.nodes, response.edges);
        this.graphLoading.set(false);
      },
      error: (err) => {
        console.warn('Graph API unavailable, falling back to sample data:', err);
        this.graphError.set('Using sample data — backend unavailable');
        this.generateSampleGraph();
        this.graphLoading.set(false);
      },
    });
  }

  private buildGraphFromResponse(apiNodes: GraphNode[], apiEdges: GraphEdge[]): void {
    if (apiNodes.length === 0) {
      this.generateSampleGraph();
      return;
    }

    const nodeIdMap = new Map<string, ExplorerNode>();

    for (let i = 0; i < apiNodes.length; i++) {
      const n = apiNodes[i];
      const color = TIER_COLORS[n.tier] ?? 0x888888;
      const importance = Math.max(0.1, Math.min(1.0, n.importance / 10)); // normalize 0-10 → 0-1

      // Volumetric golden spiral — spread nodes across a wide 3D volume
      const golden = (1 + Math.sqrt(5)) / 2;
      const theta = (2 * Math.PI * i) / golden;
      const phi = Math.acos(1 - (2 * (i + 0.5)) / apiNodes.length);
      const baseRadius = 15 + importance * 50;
      const jitter = 1.0 + (Math.sin(i * 7.31) * 0.4); // ±40% radial spread
      const radius = baseRadius * jitter;

      const pos = new THREE.Vector3(
        radius * Math.sin(phi) * Math.cos(theta),
        radius * Math.sin(phi) * Math.sin(theta),
        radius * Math.cos(phi),
      );

      const size = 0.3 + importance * 0.6;

      // Star core sprite
      const starTex = this.createStarTexture(color, 1.0);
      const starMat = new THREE.SpriteMaterial({ map: starTex, transparent: true, depthTest: false, blending: THREE.AdditiveBlending });
      const mesh = new THREE.Sprite(starMat);
      mesh.scale.set(size * 3, size * 3, 1);
      mesh.position.copy(pos);
      this.scene.add(mesh);

      // Outer glow halo sprite
      const glowTex = this.createStarTexture(color, 0.3);
      const glowMat = new THREE.SpriteMaterial({ map: glowTex, transparent: true, depthTest: false, blending: THREE.AdditiveBlending });
      const glowMesh = new THREE.Sprite(glowMat);
      glowMesh.scale.set(size * 8, size * 8, 1);
      glowMesh.position.copy(pos);
      this.scene.add(glowMesh);

      // Node label sprite (scientific designation)
      const labelSprite = this.createNodeLabel(n.id, n.tier, importance, color);
      labelSprite.position.copy(pos);
      labelSprite.position.y += size * 3 + 0.8;
      this.scene.add(labelSprite);

      const explorerNode: ExplorerNode = {
        id: n.id,
        tier: n.tier,
        text: n.textPreview,
        importance: n.importance,
        position: pos,
        velocity: new THREE.Vector3(
          (Math.random() - 0.5) * 0.005,
          (Math.random() - 0.5) * 0.005,
          (Math.random() - 0.5) * 0.005,
        ),
        mesh,
        glowMesh,
        labelSprite,
        selected: false,
        baseSize: size,
      };
      this.nodes.push(explorerNode);
      nodeIdMap.set(n.id, explorerNode);
    }

    // Build edges
    let hCount = 0, tCount = 0, eCount = 0;
    for (const e of apiEdges) {
      const fromNode = nodeIdMap.get(e.fromId);
      const toNode = nodeIdMap.get(e.toId);
      if (!fromNode || !toNode) continue;

      if (e.type === 'HEBBIAN') hCount++;
      else if (e.type === 'TEMPORAL') tCount++;
      else if (e.type === 'ENTITY') eCount++;

      const color = EDGE_TYPE_COLORS[e.type] ?? 0x888888;
      const material = e.type === 'TEMPORAL'
        ? new THREE.LineDashedMaterial({ color, transparent: true, opacity: 0.35, dashSize: 1, gapSize: 0.5 })
        : new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.3 });

      const geo = new THREE.BufferGeometry().setFromPoints([
        fromNode.position, toNode.position,
      ]);
      const line = new THREE.Line(geo, material);
      if (e.type === 'TEMPORAL') line.computeLineDistances();
      this.scene.add(line);

      // Edge labels
      let labelSprite: THREE.Sprite | undefined;
      if (e.type === 'ENTITY' && e.relation) {
        labelSprite = this.createEdgeLabel(e.relation, fromNode.position, toNode.position, color);
      }

      // Weight badge for HEBBIAN edges
      let weightSprite: THREE.Sprite | undefined;
      if (e.type === 'HEBBIAN' && e.weight > 0.05) {
        weightSprite = this.createWeightBadge(e.weight, fromNode.position, toNode.position);
      }

      this.edges.push({
        from: e.fromId, to: e.toId,
        type: e.type, weight: e.weight, relation: e.relation,
        line, labelSprite, weightSprite,
      });
    }

    this.nodeCount.set(this.nodes.length);
    this.edgeCount.set(this.edges.length);
    this.hebbianCount.set(hCount);
    this.temporalCount.set(tCount);
    this.entityCount.set(eCount);
    this.computeGraphStats();
  }

  private computeGraphStats(): void {
    if (this.nodes.length === 0) return;
    const avgImp = this.nodes.reduce((s, n) => s + n.importance, 0) / this.nodes.length;
    this.avgImportance.set(avgImp);
    const maxPossibleEdges = (this.nodes.length * (this.nodes.length - 1)) / 2;
    this.densityRatio.set(maxPossibleEdges > 0 ? this.edges.length / maxPossibleEdges : 0);
  }

  // ── Scientific label sprites ────────────────────────────

  /** Node label: tier designator + importance as a floating HUD tag */
  private createNodeLabel(id: string, tier: string, importance: number, color: number): THREE.Sprite {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d')!;
    canvas.width = 512;
    canvas.height = 160;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Short tier code
    const tierCode = tier.substring(0, 3).toUpperCase();
    const impPct = Math.round(importance * 100);
    const shortId = id.replace('mem-', '#');
    const displayText = `${tierCode} ${shortId}`;
    const impText = `⬤ ${impPct}%`;

    const hexColor = '#' + color.toString(16).padStart(6, '0');

    // Background pill for readability (no border)
    const textMetrics = (() => { ctx.font = 'bold 26px Consolas, "Courier New", monospace'; return ctx.measureText(displayText); })();
    const pillW = Math.max(textMetrics.width + 32, 140);
    const pillH = 42;
    const pillX = (canvas.width - pillW) / 2;
    const pillY = 24;
    ctx.fillStyle = 'rgba(0, 0, 0, 0.5)';
    ctx.beginPath();
    ctx.roundRect(pillX, pillY, pillW, pillH, 10);
    ctx.fill();

    // ID label
    ctx.globalAlpha = 0.9;
    ctx.font = 'bold 26px Consolas, "Courier New", monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = hexColor;
    ctx.fillText(displayText, canvas.width / 2, pillY + pillH / 2);

    // Importance bar
    ctx.globalAlpha = 0.5;
    const barW = 80;
    const barH = 4;
    const barX = (canvas.width - barW) / 2;
    const barY = pillY + pillH + 10;
    ctx.fillStyle = 'rgba(255, 255, 255, 0.12)';
    ctx.beginPath();
    ctx.roundRect(barX, barY, barW, barH, 2);
    ctx.fill();
    ctx.fillStyle = hexColor;
    ctx.beginPath();
    ctx.roundRect(barX, barY, barW * Math.min(1, importance), barH, 2);
    ctx.fill();

    // Importance value
    ctx.globalAlpha = 0.7;
    ctx.font = '600 18px Consolas, "Courier New", monospace';
    ctx.fillStyle = '#bbc4dd';
    ctx.fillText(impText, canvas.width / 2, barY + barH + 16);

    ctx.globalAlpha = 1.0;

    const texture = new THREE.CanvasTexture(canvas);
    texture.minFilter = THREE.LinearFilter;
    const material = new THREE.SpriteMaterial({
      map: texture,
      transparent: true,
      depthTest: false,
    });
    const sprite = new THREE.Sprite(material);
    sprite.scale.set(8, 2.5, 1);
    return sprite;
  }

  /** Weight badge at edge midpoint */
  private createWeightBadge(weight: number, from: THREE.Vector3, to: THREE.Vector3): THREE.Sprite {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d')!;
    canvas.width = 192;
    canvas.height = 80;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Background
    ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
    ctx.beginPath();
    ctx.roundRect(40, 16, 112, 48, 10);
    ctx.fill();

    // Weight value
    ctx.font = 'bold 28px Consolas, "Courier New", monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = 'rgba(255, 255, 255, 0.85)';
    ctx.fillText(weight.toFixed(2), canvas.width / 2, canvas.height / 2);

    const texture = new THREE.CanvasTexture(canvas);
    texture.minFilter = THREE.LinearFilter;
    const material = new THREE.SpriteMaterial({
      map: texture,
      transparent: true,
      depthTest: false,
    });
    const sprite = new THREE.Sprite(material);
    sprite.scale.set(4, 1.6, 1);

    const mid = new THREE.Vector3().addVectors(from, to).multiplyScalar(0.5);
    mid.y -= 1.0;
    sprite.position.copy(mid);
    this.scene.add(sprite);
    return sprite;
  }

  /** Generate a radial-gradient star texture for a glowing point */
  private createStarTexture(color: number, intensity: number): THREE.CanvasTexture {
    const size = 128;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d')!;

    const r = (color >> 16) & 0xff;
    const g = (color >> 8) & 0xff;
    const b = color & 0xff;

    const cx = size / 2;
    const cy = size / 2;
    const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, size / 2);

    if (intensity > 0.6) {
      // Core star: bright white center → tier color → transparent
      grad.addColorStop(0.0, `rgba(255, 255, 255, ${intensity})`);
      grad.addColorStop(0.15, `rgba(${r}, ${g}, ${b}, ${intensity * 0.9})`);
      grad.addColorStop(0.4, `rgba(${r}, ${g}, ${b}, ${intensity * 0.4})`);
      grad.addColorStop(1.0, `rgba(${r}, ${g}, ${b}, 0)`);
    } else {
      // Glow halo: soft color → transparent
      grad.addColorStop(0.0, `rgba(${r}, ${g}, ${b}, ${intensity})`);
      grad.addColorStop(0.3, `rgba(${r}, ${g}, ${b}, ${intensity * 0.5})`);
      grad.addColorStop(1.0, `rgba(${r}, ${g}, ${b}, 0)`);
    }

    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, size, size);

    // Add cross-shaped diffraction spikes for star effect (core only)
    if (intensity > 0.6) {
      ctx.globalCompositeOperation = 'lighter';
      const spikeGrad = ctx.createRadialGradient(cx, cy, 0, cx, cy, size / 2);
      spikeGrad.addColorStop(0.0, `rgba(255, 255, 255, 0.4)`);
      spikeGrad.addColorStop(0.5, `rgba(${r}, ${g}, ${b}, 0.05)`);
      spikeGrad.addColorStop(1.0, `rgba(${r}, ${g}, ${b}, 0)`);

      ctx.fillStyle = spikeGrad;
      // Horizontal spike
      ctx.fillRect(0, cy - 1, size, 2);
      // Vertical spike
      ctx.fillRect(cx - 1, 0, 2, size);
    }

    const texture = new THREE.CanvasTexture(canvas);
    texture.minFilter = THREE.LinearFilter;
    return texture;
  }

  /** Entity edge relation label */
  private createEdgeLabel(text: string, from: THREE.Vector3, to: THREE.Vector3, color: number): THREE.Sprite {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d')!;
    canvas.width = 256;
    canvas.height = 40;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Small, clean text — no background, no border
    const hexColor = '#' + color.toString(16).padStart(6, '0');
    ctx.font = '600 16px Consolas, "Courier New", monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.globalAlpha = 0.85;
    ctx.fillStyle = hexColor;
    ctx.fillText(text, canvas.width / 2, canvas.height / 2);

    const texture = new THREE.CanvasTexture(canvas);
    texture.minFilter = THREE.LinearFilter;
    const material = new THREE.SpriteMaterial({
      map: texture,
      transparent: true,
      depthTest: false,
    });
    const sprite = new THREE.Sprite(material);
    sprite.scale.set(4, 0.7, 1);

    // Position right on the midpoint of the connecting line
    const midpoint = new THREE.Vector3().addVectors(from, to).multiplyScalar(0.5);
    midpoint.y += 0.4;  // just barely above the line
    sprite.position.copy(midpoint);

    this.scene.add(sprite);
    return sprite;
  }

  // ── Scene cleanup ───────────────────────────────────────

  private clearScene(): void {
    for (const node of this.nodes) {
      this.scene.remove(node.mesh);
      this.scene.remove(node.glowMesh);
      this.scene.remove(node.labelSprite);
      (node.mesh.material as THREE.SpriteMaterial).map?.dispose();
      node.mesh.material.dispose();
      (node.glowMesh.material as THREE.SpriteMaterial).map?.dispose();
      node.glowMesh.material.dispose();
      (node.labelSprite.material as THREE.SpriteMaterial).map?.dispose();
      node.labelSprite.material.dispose();
    }
    for (const edge of this.edges) {
      this.scene.remove(edge.line);
      edge.line.geometry.dispose();
      if (edge.labelSprite) {
        this.scene.remove(edge.labelSprite);
        (edge.labelSprite.material as THREE.SpriteMaterial).map?.dispose();
        edge.labelSprite.material.dispose();
      }
      if (edge.weightSprite) {
        this.scene.remove(edge.weightSprite);
        (edge.weightSprite.material as THREE.SpriteMaterial).map?.dispose();
        edge.weightSprite.material.dispose();
      }
    }
    this.nodes = [];
    this.edges = [];
    this.nodeCount.set(0);
    this.edgeCount.set(0);
    this.selectedNode.set(null);
    this.selectedEdge.set(null);
  }

  // ── Sample graph fallback ───────────────────────────────

  private generateSampleGraph(): void {
    const tiers = Object.keys(TIER_COLORS);
    const count = 80;

    for (let i = 0; i < count; i++) {
      const tier = tiers[Math.floor(Math.random() * tiers.length)];
      const color = TIER_COLORS[tier];
      const importance = 0.3 + Math.random() * 0.7;
      const radius = 15 + Math.random() * 65;
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);

      const pos = new THREE.Vector3(
        radius * Math.sin(phi) * Math.cos(theta),
        radius * Math.sin(phi) * Math.sin(theta),
        radius * Math.cos(phi),
      );

      const size = 0.3 + importance * 0.6;

      // Star core sprite
      const starTex = this.createStarTexture(color, 1.0);
      const starMat = new THREE.SpriteMaterial({ map: starTex, transparent: true, depthTest: false, blending: THREE.AdditiveBlending });
      const mesh = new THREE.Sprite(starMat);
      mesh.scale.set(size * 3, size * 3, 1);
      mesh.position.copy(pos);
      this.scene.add(mesh);

      // Outer glow halo sprite
      const glowTex = this.createStarTexture(color, 0.3);
      const glowMat = new THREE.SpriteMaterial({ map: glowTex, transparent: true, depthTest: false, blending: THREE.AdditiveBlending });
      const glowMesh = new THREE.Sprite(glowMat);
      glowMesh.scale.set(size * 8, size * 8, 1);
      glowMesh.position.copy(pos);
      this.scene.add(glowMesh);

      const labelSprite = this.createNodeLabel(`mem-${i}`, tier, importance, color);
      labelSprite.position.copy(pos);
      labelSprite.position.y += size * 3 + 0.8;
      this.scene.add(labelSprite);

      this.nodes.push({
        id: `mem-${i}`,
        tier,
        text: `Sample ${tier.toLowerCase()} memory #${i}`,
        importance,
        position: pos,
        velocity: new THREE.Vector3(
          (Math.random() - 0.5) * 0.008,
          (Math.random() - 0.5) * 0.008,
          (Math.random() - 0.5) * 0.008,
        ),
        mesh,
        glowMesh,
        labelSprite,
        selected: false,
        baseSize: size,
      });
    }

    // Generate edges
    const types = ['HEBBIAN', 'TEMPORAL', 'ENTITY'];
    const sampleRelations = ['MANAGES', 'WORKS_ON', 'KNOWS', 'DEPENDS_ON', 'USES'];
    let hCount = 0, tCount = 0, eCount = 0;
    for (let i = 0; i < this.nodes.length; i++) {
      for (let j = i + 1; j < this.nodes.length; j++) {
        const dist = this.nodes[i].position.distanceTo(this.nodes[j].position);
        if (dist < 15 && Math.random() > 0.6) {
          const type = types[Math.floor(Math.random() * types.length)];
          const color = EDGE_TYPE_COLORS[type];
          const weight = 0.5 + Math.random() * 3;
          const relation = type === 'ENTITY'
            ? sampleRelations[Math.floor(Math.random() * sampleRelations.length)]
            : null;

          if (type === 'HEBBIAN') hCount++;
          else if (type === 'TEMPORAL') tCount++;
          else if (type === 'ENTITY') eCount++;

          const material = type === 'TEMPORAL'
            ? new THREE.LineDashedMaterial({ color, transparent: true, opacity: 0.35, dashSize: 1, gapSize: 0.5 })
            : new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.3 });

          const geo = new THREE.BufferGeometry().setFromPoints([
            this.nodes[i].position, this.nodes[j].position,
          ]);
          const line = new THREE.Line(geo, material);
          if (type === 'TEMPORAL') line.computeLineDistances();
          this.scene.add(line);

          let labelSprite: THREE.Sprite | undefined;
          if (type === 'ENTITY' && relation) {
            labelSprite = this.createEdgeLabel(relation, this.nodes[i].position, this.nodes[j].position, color);
          }

          let weightSprite: THREE.Sprite | undefined;
          if (type === 'HEBBIAN') {
            weightSprite = this.createWeightBadge(weight, this.nodes[i].position, this.nodes[j].position);
          }

          this.edges.push({
            from: this.nodes[i].id, to: this.nodes[j].id,
            type, weight, relation, line, labelSprite, weightSprite,
          });
        }
      }
    }

    this.nodeCount.set(this.nodes.length);
    this.edgeCount.set(this.edges.length);
    this.hebbianCount.set(hCount);
    this.temporalCount.set(tCount);
    this.entityCount.set(eCount);
    this.computeGraphStats();
  }

  // ── Animation loop ──────────────────────────────────────

  private animate(): void {
    this.animationId = requestAnimationFrame(() => this.animate());
    this.timer.update(performance.now());
    const delta = this.timer.getDelta();

    // Auto orbit when not dragging
    if (!this.isDragging) {
      this.orbitTheta += 0.02 * delta;
    }

    // Fly-to animation: interpolate from start to end
    if (this.flyToPos && this.flyProgress < 1) {
      this.flyProgress = Math.min(1, this.flyProgress + delta * 2.0);
      const t = this.easeInOutCubic(this.flyProgress);
      // Directly interpolate between start and end (not cumulative)
      this.lookAtTarget.lerpVectors(this.flyFromPos, this.flyToPos, t);
      this.orbitRadius = this.flyFromRadius + (this.flyToRadius - this.flyFromRadius) * t;
      if (this.flyProgress >= 1) {
        this.lookAtTarget.copy(this.flyToPos);
        this.orbitRadius = this.flyToRadius;
        this.flyToPos = null;
      }
    }

    this.camera.position.x = this.lookAtTarget.x + this.orbitRadius * Math.sin(this.orbitPhi) * Math.cos(this.orbitTheta);
    this.camera.position.y = this.lookAtTarget.y + this.orbitRadius * Math.cos(this.orbitPhi);
    this.camera.position.z = this.lookAtTarget.z + this.orbitRadius * Math.sin(this.orbitPhi) * Math.sin(this.orbitTheta);
    this.camera.lookAt(this.lookAtTarget);

    // Animate dust rotation (slow)
    if (this.dustParticles) {
      this.dustParticles.rotation.y += 0.001 * delta;
    }

    // Labels always visible when toggled on (no distance threshold)
    const showNodeLabels = this.showLabels();
    const camPos = this.camera.position;

    // Animate nodes (star pulsing + position)
    const time = Date.now() * 0.001;
    for (let i = 0; i < this.nodes.length; i++) {
      const node = this.nodes[i];
      node.position.add(node.velocity);
      node.mesh.position.copy(node.position);
      node.glowMesh.position.copy(node.position);
      if (node.position.length() > 120) node.velocity.multiplyScalar(-1);

      // Gentle star pulsing — each node gets a unique phase
      const pulse = 1.0 + 0.15 * Math.sin(time * 1.5 + i * 0.7);
      const coreScale = node.baseSize * 3 * pulse;
      node.mesh.scale.set(coreScale, coreScale, 1);
      const glowScale = node.baseSize * 8 * pulse;
      node.glowMesh.scale.set(glowScale, glowScale, 1);

      // Update label position — float above the star
      node.labelSprite.position.copy(node.position);
      node.labelSprite.position.y += node.baseSize * 4 + 2.0;
      node.labelSprite.visible = showNodeLabels;

      // Dynamic scale: keep labels the same apparent size on screen
      if (showNodeLabels) {
        const dist = camPos.distanceTo(node.labelSprite.position);
        const s = dist * 0.06;
        node.labelSprite.scale.set(s * 3.2, s, 1);
      }
    }

    // Update edges
    for (const edge of this.edges) {
      const fromNode = this.nodes.find(n => n.id === edge.from);
      const toNode = this.nodes.find(n => n.id === edge.to);
      if (fromNode && toNode) {
        const positions = edge.line.geometry.attributes['position'] as THREE.BufferAttribute;
        positions.setXYZ(0, fromNode.position.x, fromNode.position.y, fromNode.position.z);
        positions.setXYZ(1, toNode.position.x, toNode.position.y, toNode.position.z);
        positions.needsUpdate = true;

        // Update label sprite positions + dynamic scale
        if (edge.labelSprite) {
          const mid = new THREE.Vector3().addVectors(fromNode.position, toNode.position).multiplyScalar(0.5);
          mid.y += 0.4;
          edge.labelSprite.position.copy(mid);
          const dist = camPos.distanceTo(mid);
          const s = dist * 0.035;
          edge.labelSprite.scale.set(s * 2.5, s * 0.5, 1);
        }
        if (edge.weightSprite) {
          const mid = new THREE.Vector3().addVectors(fromNode.position, toNode.position).multiplyScalar(0.5);
          mid.y -= 1.2;
          edge.weightSprite.position.copy(mid);
          const dist = camPos.distanceTo(mid);
          const s = dist * 0.04;
          edge.weightSprite.scale.set(s * 2.5, s, 1);
        }
      }

      // Layer visibility
      const visible =
        (edge.type === 'HEBBIAN' && this.showHebbian()) ||
        (edge.type === 'TEMPORAL' && this.showTemporal()) ||
        (edge.type === 'ENTITY' && this.showEntity());
      edge.line.visible = visible;
      if (edge.labelSprite) {
        edge.labelSprite.visible = visible && this.orbitRadius < 150;
      }
      if (edge.weightSprite) {
        edge.weightSprite.visible = visible && showNodeLabels;
      }
    }

    this.renderer.render(this.scene, this.camera);
  }
}
