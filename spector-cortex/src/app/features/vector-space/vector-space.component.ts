import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { ThemeService } from '../../core/services/theme.service';
import * as THREE from 'three';

const TIER_COLORS: Record<string, number> = {
  WORKING: 0xffb74d,
  EPISODIC: 0x66bb6a,
  SEMANTIC: 0x42a5f5,
  PROCEDURAL: 0xab47bc,
};

@Component({
  selector: 'cortex-vector-space',
  templateUrl: './vector-space.component.html',
  styleUrl: './vector-space.component.scss',
})
export class VectorSpaceComponent implements AfterViewInit, OnDestroy {

  @ViewChild('canvasContainer', { static: true })
  private canvasContainer!: ElementRef<HTMLDivElement>;

  private readonly state = inject(CortexStateService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  private scene!: THREE.Scene;
  private camera!: THREE.PerspectiveCamera;
  private renderer!: THREE.WebGLRenderer;
  private pointsMesh!: THREE.Points;
  private queryDot: THREE.Mesh | null = null;
  private nearestLines: THREE.Line[] = [];
  private animationId = 0;
  private mouseX = 0;
  private mouseY = 0;
  private clock = new THREE.Clock();

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.initScene();
    this.buildPointCloud();
    this.createQueryDot();
    this.animate();

    effect(() => {
      const qv = this.state.queryVector();
      if (qv && this.queryDot) {
        this.queryDot.position.set(qv[0], qv[1], qv[2]);
        (this.queryDot.material as THREE.MeshBasicMaterial).opacity = 1;
        this.updateNearestNeighborLines(qv);
      }
    });

    effect(() => {
      const points = this.state.vectorPoints();
      if (points.length > 0) this.buildPointCloud();
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
    this.camera = new THREE.PerspectiveCamera(55, width / height, 0.1, 500);
    this.camera.position.z = 70;

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setClearColor(0x000000, 0);
    container.appendChild(this.renderer.domElement);

    const observer = new ResizeObserver(() => {
      const w = container.clientWidth;
      const h = container.clientHeight;
      this.camera.aspect = w / h;
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(w, h);
    });
    observer.observe(container);
  }

  private buildPointCloud(): void {
    if (this.pointsMesh) this.scene.remove(this.pointsMesh);

    const points = this.state.vectorPoints();
    if (points.length === 0) return;

    const positions = new Float32Array(points.length * 3);
    const colors = new Float32Array(points.length * 3);
    const sizes = new Float32Array(points.length);

    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      positions[i * 3] = p.position[0];
      positions[i * 3 + 1] = p.position[1];
      positions[i * 3 + 2] = p.position[2];

      const color = new THREE.Color(TIER_COLORS[p.tier] || 0xffffff);
      colors[i * 3] = color.r;
      colors[i * 3 + 1] = color.g;
      colors[i * 3 + 2] = color.b;

      sizes[i] = 1.5 + p.importance * 3;
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    geometry.setAttribute('size', new THREE.BufferAttribute(sizes, 1));

    const material = new THREE.PointsMaterial({
      size: 2,
      vertexColors: true,
      transparent: true,
      opacity: 0.7,
      sizeAttenuation: true,
    });

    this.pointsMesh = new THREE.Points(geometry, material);
    this.scene.add(this.pointsMesh);
  }

  private createQueryDot(): void {
    const geometry = new THREE.SphereGeometry(1.2, 16, 16);
    const material = new THREE.MeshBasicMaterial({
      color: 0xffffff, transparent: true, opacity: 0,
    });
    this.queryDot = new THREE.Mesh(geometry, material);
    this.scene.add(this.queryDot);

    // Outer ring
    const ringGeometry = new THREE.RingGeometry(1.8, 2.2, 32);
    const ringMaterial = new THREE.MeshBasicMaterial({
      color: 0xffffff, transparent: true, opacity: 0, side: THREE.DoubleSide,
    });
    const ring = new THREE.Mesh(ringGeometry, ringMaterial);
    this.queryDot.add(ring);
  }

  private updateNearestNeighborLines(queryPos: [number, number, number]): void {
    this.nearestLines.forEach(l => this.scene.remove(l));
    this.nearestLines = [];

    const points = this.state.vectorPoints();
    const qVec = new THREE.Vector3(queryPos[0], queryPos[1], queryPos[2]);

    // Find 5 nearest
    const sorted = points
      .map((p, i) => ({
        index: i,
        dist: qVec.distanceTo(new THREE.Vector3(p.position[0], p.position[1], p.position[2])),
      }))
      .sort((a, b) => a.dist - b.dist)
      .slice(0, 5);

    for (const nearest of sorted) {
      const p = points[nearest.index];
      const material = new THREE.LineBasicMaterial({
        color: 0xffffff, transparent: true, opacity: 0.4,
      });
      const geometry = new THREE.BufferGeometry().setFromPoints([
        qVec,
        new THREE.Vector3(p.position[0], p.position[1], p.position[2]),
      ]);
      const line = new THREE.Line(geometry, material);
      this.scene.add(line);
      this.nearestLines.push(line);
    }
  }

  private animate(): void {
    this.animationId = requestAnimationFrame(() => this.animate());
    const time = this.clock.getElapsedTime();

    this.camera.position.x = 70 * Math.sin(time * 0.03) + this.mouseX * 10;
    this.camera.position.y = 30 * Math.sin(time * 0.02) + this.mouseY * 10;
    this.camera.lookAt(0, 0, 0);

    // Pulse query dot
    if (this.queryDot) {
      const mat = this.queryDot.material as THREE.MeshBasicMaterial;
      if (mat.opacity > 0.01) {
        mat.opacity *= 0.998;
      }
      this.queryDot.scale.setScalar(1 + Math.sin(time * 3) * 0.1);
    }

    // Fade nearest lines
    for (const line of this.nearestLines) {
      const mat = line.material as THREE.LineBasicMaterial;
      if (mat.opacity > 0.05) mat.opacity *= 0.999;
    }

    this.renderer.render(this.scene, this.camera);
  }
}
