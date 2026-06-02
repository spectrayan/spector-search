import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { FormsModule } from '@angular/forms';
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
  imports: [MatCheckboxModule, FormsModule],
  templateUrl: './vector-space.component.html',
  styleUrl: './vector-space.component.scss',
})
export class VectorSpaceComponent implements AfterViewInit, OnDestroy {

  @ViewChild('canvasContainer', { static: true })
  private canvasContainer!: ElementRef<HTMLDivElement>;

  protected readonly state = inject(CortexStateService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  private scene!: THREE.Scene;
  private camera!: THREE.PerspectiveCamera;
  private renderer!: THREE.WebGLRenderer;
  private pointsMesh!: THREE.Points;
  private queryDot: THREE.Mesh | null = null;
  private nearestLines: THREE.Line[] = [];
  private axesGroup!: THREE.Group;
  private labelsGroup!: THREE.Group;
  private animationId = 0;
  private mouseX = 0;
  private mouseY = 0;
  private timer = new THREE.Timer();

  // ── Orbit & zoom state ──
  private isDragging = false;
  private orbitTheta = 0;
  private orbitPhi = Math.PI / 3;
  private orbitRadius = 70;
  private dragStartX = 0;
  private dragStartY = 0;
  private dragStartTheta = 0;
  private dragStartPhi = 0;
  private autoOrbitSpeed = 0.03;

  constructor() {
    effect(() => {
      const qv = this.state.queryVector();
      if (qv && this.queryDot && this.scene) {
        this.queryDot.position.set(qv[0], qv[1], qv[2]);
        (this.queryDot.material as THREE.MeshBasicMaterial).opacity = 1;
        this.updateNearestNeighborLines(qv);
      }
    });

    effect(() => {
      const points = this.state.vectorPoints();
      if (points.length > 0 && this.scene) this.buildPointCloud();
    });
  }

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.initScene();
    this.buildAxesGrid();
    this.buildDimensionLabels();
    this.buildPointCloud();
    this.createQueryDot();
    this.animate();
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.animationId);
    this.renderer?.dispose();
  }

  onMouseMove(event: MouseEvent): void {
    const rect = this.canvasContainer.nativeElement.getBoundingClientRect();
    this.mouseX = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.mouseY = -((event.clientY - rect.top) / rect.height) * 2 + 1;

    if (this.isDragging) {
      const dx = (event.clientX - this.dragStartX) * 0.005;
      const dy = (event.clientY - this.dragStartY) * 0.005;
      this.orbitTheta = this.dragStartTheta - dx;
      this.orbitPhi = Math.max(0.1, Math.min(Math.PI - 0.1,
        this.dragStartPhi + dy));
    }
  }

  onMouseDown(event: MouseEvent): void {
    this.isDragging = true;
    this.dragStartX = event.clientX;
    this.dragStartY = event.clientY;
    this.dragStartTheta = this.orbitTheta;
    this.dragStartPhi = this.orbitPhi;
    event.preventDefault();
  }

  onMouseUp(): void {
    this.isDragging = false;
  }

  onWheel(event: WheelEvent): void {
    this.orbitRadius = Math.max(15, Math.min(200,
      this.orbitRadius + event.deltaY * 0.05));
    event.preventDefault();
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
    this.timer.update(performance.now() / 1000);
    const delta = this.timer.getDelta();
    const time = this.timer.getElapsed();

    // Camera orbit — auto-rotate when not dragging, user-controlled when dragging
    if (!this.isDragging) {
      this.orbitTheta += this.autoOrbitSpeed * delta;
    }
    this.camera.position.x = this.orbitRadius * Math.sin(this.orbitPhi) * Math.cos(this.orbitTheta);
    this.camera.position.y = this.orbitRadius * Math.cos(this.orbitPhi);
    this.camera.position.z = this.orbitRadius * Math.sin(this.orbitPhi) * Math.sin(this.orbitTheta);
    this.camera.lookAt(0, 0, 0);

    // ── Layer visibility ──
    const layers = this.state.vectorLayers();

    if (this.queryDot) this.queryDot.visible = layers.queryDot;
    for (const line of this.nearestLines) line.visible = layers.knnLines;
    if (this.axesGroup) this.axesGroup.visible = layers.axesGrid;
    if (this.labelsGroup) this.labelsGroup.visible = layers.labels;

    // Pulse query dot
    if (this.queryDot && layers.queryDot) {
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

    // Labels always face camera
    if (this.labelsGroup) {
      this.labelsGroup.children.forEach(child => {
        child.quaternion.copy(this.camera.quaternion);
      });
    }

    this.renderer.render(this.scene, this.camera);
  }

  // ── Axes grid ──
  private buildAxesGrid(): void {
    this.axesGroup = new THREE.Group();

    const size = 30;
    const gridColor = 0x444466;

    // Three axis lines
    const axisMaterial = new THREE.LineBasicMaterial({ color: 0x666688, transparent: true, opacity: 0.4 });

    // X axis (red tint)
    const xGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-size, 0, 0), new THREE.Vector3(size, 0, 0),
    ]);
    const xLine = new THREE.Line(xGeo, new THREE.LineBasicMaterial({ color: 0xff6666, transparent: true, opacity: 0.3 }));
    this.axesGroup.add(xLine);

    // Y axis (green tint)
    const yGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0, -size, 0), new THREE.Vector3(0, size, 0),
    ]);
    const yLine = new THREE.Line(yGeo, new THREE.LineBasicMaterial({ color: 0x66ff66, transparent: true, opacity: 0.3 }));
    this.axesGroup.add(yLine);

    // Z axis (blue tint)
    const zGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0, 0, -size), new THREE.Vector3(0, 0, size),
    ]);
    const zLine = new THREE.Line(zGeo, new THREE.LineBasicMaterial({ color: 0x6666ff, transparent: true, opacity: 0.3 }));
    this.axesGroup.add(zLine);

    // Grid rings at intervals
    for (const r of [10, 20, 30]) {
      const ringGeo = new THREE.RingGeometry(r - 0.1, r + 0.1, 64);
      const ringMat = new THREE.MeshBasicMaterial({
        color: gridColor, transparent: true, opacity: 0.08, side: THREE.DoubleSide,
      });
      const ring = new THREE.Mesh(ringGeo, ringMat);
      ring.rotation.x = -Math.PI / 2;
      this.axesGroup.add(ring);
    }

    this.scene.add(this.axesGroup);
  }

  // ── Dimension labels ──
  private buildDimensionLabels(): void {
    this.labelsGroup = new THREE.Group();

    const labels: Array<{ text: string; pos: THREE.Vector3; color: number }> = [
      { text: 'dim₀', pos: new THREE.Vector3(34, 0, 0), color: 0xff6666 },
      { text: 'dim₁', pos: new THREE.Vector3(0, 34, 0), color: 0x66ff66 },
      { text: 'dim₂', pos: new THREE.Vector3(0, 0, 34), color: 0x6666ff },
    ];

    for (const label of labels) {
      const canvas = document.createElement('canvas');
      canvas.width = 64;
      canvas.height = 24;
      const ctx = canvas.getContext('2d')!;
      ctx.fillStyle = '#' + label.color.toString(16).padStart(6, '0');
      ctx.font = 'bold 16px Inter, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(label.text, 32, 12);

      const texture = new THREE.CanvasTexture(canvas);
      const spriteMat = new THREE.SpriteMaterial({
        map: texture, transparent: true, opacity: 0.7,
      });
      const sprite = new THREE.Sprite(spriteMat);
      sprite.position.copy(label.pos);
      sprite.scale.set(8, 3, 1);
      this.labelsGroup.add(sprite);
    }

    this.scene.add(this.labelsGroup);
  }
}
