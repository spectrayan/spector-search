# spector-spring 🍃

> **Spring Boot starter and Spring AI integration auto-configurations for Spector.**

`spector-spring` registers Spector as a native Spring Boot starter dependency. It auto-configures the in-process `SpectorEngine` bean, maps application properties, and implements Spring AI's core `VectorStore` interfaces for plug-and-play RAG architectures.

---

## 🏗️ Core Architecture & Roles

1. **Auto-Configuration (`SpectorAutoConfiguration`):** Reads environment configurations from `application.yml` and instantiates the `SpectorEngine` lifecycle beans automatically.
2. **Spring AI Integration (`SpectorVectorStore`):** Implements Spring AI's standard `VectorStore` contract:
   - `add(List<Document> documents)`
   - `similaritySearch(SearchRequest request)`

---

## 🚀 Spring Configurations

### 1. Register Starter Dependency (Maven)
```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Properties (`application.yml`)
```yaml
spring:
  ai:
    vector:
      spector:
        dimensions: 384
        capacity: 100000
        quantization: SCALAR_INT8
        gpu-enabled: true
```

### 3. Autowire and Search
```java
@Autowired
private VectorStore vectorStore;

public List<Document> search(String query) {
    return vectorStore.similaritySearch(
        SearchRequest.query(query).withTopK(5)
    );
}
```
