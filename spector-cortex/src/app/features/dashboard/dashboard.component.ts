import { Component, inject, OnInit, OnDestroy, effect } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { HeaderComponent } from '../header/header.component';
import { NeuralGraphComponent } from '../neural-graph/neural-graph.component';
import { VectorSpaceComponent } from '../vector-space/vector-space.component';
import { PipelineFunnelComponent } from '../pipeline-funnel/pipeline-funnel.component';
import { SimdPanelComponent } from '../simd-panel/simd-panel.component';
import { MemoryHeatmapComponent } from '../memory-heatmap/memory-heatmap.component';
import { ProfileRadarComponent } from '../profile-radar/profile-radar.component';
import { QueryInputComponent } from '../query-input/query-input.component';
import { QueryHistoryComponent } from '../query-history/query-history.component';
import { MetricsChartComponent } from '../metrics-chart/metrics-chart.component';
import { DecayCurveComponent } from '../decay-curve/decay-curve.component';
import { ZeigarnikTrackerComponent } from '../zeigarnik-tracker/zeigarnik-tracker.component';
import { HabituationMeterComponent } from '../habituation-meter/habituation-meter.component';
import { MemoryDiffComponent } from '../memory-diff/memory-diff.component';
import { GpuTimelineComponent } from '../gpu-timeline/gpu-timeline.component';
import { ClusterViewComponent } from '../cluster-view/cluster-view.component';
import { MockDataService } from '../../core/services/mock-data.service';
import { CortexStateService } from '../../core/services/cortex-state.service';

@Component({
  selector: 'cortex-dashboard',
  imports: [
    MatCardModule,
    MatIconModule,
    HeaderComponent,
    NeuralGraphComponent,
    VectorSpaceComponent,
    PipelineFunnelComponent,
    SimdPanelComponent,
    MemoryHeatmapComponent,
    ProfileRadarComponent,
    QueryInputComponent,
    QueryHistoryComponent,
    MetricsChartComponent,
    DecayCurveComponent,
    ZeigarnikTrackerComponent,
    HabituationMeterComponent,
    MemoryDiffComponent,
    GpuTimelineComponent,
    ClusterViewComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly mockData = inject(MockDataService);
  protected readonly state = inject(CortexStateService);

  constructor() {
    // Switch view mode based on the node selector dropdown
    effect(() => {
      const selected = this.state.selectedNode();
      this.state.viewMode.set(selected === 'cluster' ? 'cluster' : 'dashboard');
    });
  }

  ngOnInit(): void {
    if (this.state.useMockData()) {
      this.mockData.start();
    }
  }

  ngOnDestroy(): void {
    this.mockData.stop();
  }
}
