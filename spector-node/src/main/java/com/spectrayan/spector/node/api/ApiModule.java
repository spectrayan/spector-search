package com.spectrayan.spector.node.api;

/**
 * Pluggable API endpoint module for Spector node.
 *
 * <p>Each module represents a group of related endpoints (search, ingest, etc.)
 * that are registered on the Armeria server under a versioned path prefix.</p>
 *
 * <h3>API Versioning</h3>
 * <p>{@link com.spectrayan.spector.node.SpectorNode} registers modules at:</p>
 * <pre>{@code
 *   /api/v1 + module.pathPrefix()
 * }</pre>
 *
 * <p>For API v2 with breaking changes, create new endpoint classes implementing
 * this interface and register them at {@code /api/v2}.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 *   public class SearchEndpoint implements ApiModule {
 *       @Override public String pathPrefix() { return ""; }
 *
 *       @Post("/search")
 *       public HttpResponse search(SearchRequest request) { ... }
 *   }
 * }</pre>
 */
public interface ApiModule {

    /**
     * Path prefix for this module's endpoints.
     *
     * <p>Combined with the API version prefix by SpectorNode. For example,
     * if this returns {@code ""}, endpoints are at {@code /api/v1/...}.
     * If this returns {@code "/admin"}, endpoints are at {@code /api/v1/admin/...}.</p>
     *
     * @return the path prefix (empty string for root, or "/subpath")
     */
    String pathPrefix();
}
