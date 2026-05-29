## Description
<!-- Describe your changes in detail -->
<!-- Include motivation and context if it's a new feature -->

## Related Issue
<!-- Link to the issue here: "Closes #123" -->

## Type of Change
<!-- Check the relevant option -->
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Performance improvement (change that improves throughput or latency)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update

## Module(s) Affected
<!-- Check all that apply -->
- [ ] `spector-core` (SIMD kernels)
- [ ] `spector-storage` (Panama storage)
- [ ] `spector-index` (HNSW / BM25)
- [ ] `spector-query` (query orchestration)
- [ ] `spector-engine` (engine facade)
- [ ] `spector-node` (REST API)
- [ ] `spector-bench` (benchmarks)

## Checklist
- [ ] My code follows the code style of this project
- [ ] I have added Javadoc for all public classes/methods
- [ ] I have added tests to cover my changes
- [ ] All new and existing tests passed (`mvn test`)
- [ ] No hardcoded secrets or credentials are included
- [ ] JMH benchmark results included (if performance-related)
