/**
 * Copyright (c) 2016, SIREn Solutions. All Rights Reserved.
 *
 * This file is part of the SIREn project.
 *
 * SIREn is a free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * SIREn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package solutions.siren.join.action.coordinate;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.TransportSearchAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import solutions.siren.join.action.admin.cache.FilterJoinCacheService;
import solutions.siren.join.action.coordinate.execution.*;

import java.util.Map;

/**
 * The transport action for a coordinated search.
 */
public class TransportCoordinateSearchAction extends BaseTransportCoordinateSearchAction<SearchRequest, SearchResponse> {

  private final TransportSearchAction searchAction;

  private final FilterJoinCacheService cacheService;

  @Inject
  public TransportCoordinateSearchAction(Settings settings, ThreadPool threadPool,
                                         TransportService transportService, FilterJoinCacheService cacheService,
                                         ActionFilters actionFilters, TransportSearchAction searchAction,
                                         IndexNameExpressionResolver indexNameExpressionResolver, Client client) {
    super(settings, CoordinateSearchAction.NAME, threadPool, transportService, actionFilters,
            indexNameExpressionResolver, client, SearchRequest.class);
    this.searchAction = searchAction;
    this.cacheService = cacheService;
  }

  @Override
  protected void doExecute(final SearchRequest request, final ActionListener<SearchResponse> listener) {
    logger.debug("{}: Execute coordinated search action", Thread.currentThread().getName());

    // A reference to the listener that will be used - can be overwritten to reference a CoordinateSearchListener
    ActionListener<SearchResponse> actionListener = listener;

    // Retrieve the singleton instance of the filterjoin cache
    FilterJoinCache cache = cacheService.getCacheInstance();

    // Parse query source
    Tuple<XContentType, Map<String, Object>> parsedSource = this.parseSource(request.source());
    if (parsedSource != null) { // can be null if this is a uri search (query parameter in extraSource)
      Map<String, Object> map = parsedSource.v2();

      // Unwrap "wrapper" queries
      WrapperQueryVisitor wrapperVisitor = new WrapperQueryVisitor(map);
      wrapperVisitor.traverse();

      // Query planning and execution of filter joins
      SourceMapVisitor mapVisitor = new SourceMapVisitor(map);
      mapVisitor.traverse();
      FilterJoinVisitor joinVisitor = new FilterJoinVisitor(client, mapVisitor.getFilterJoinTree(), cache, request);
      joinVisitor.traverse();

      // Wraps the listener with our own to inject metadata information in the response
      CoordinateSearchListener coordinateSearchListener = new CoordinateSearchListener(listener);
      coordinateSearchListener.setMetadata(joinVisitor.getMetadata());
      actionListener = coordinateSearchListener;

      // Filter joins have been replaced by a binary terms filter
      // Rebuild the query source, and delegate the execution of the search action
      request.source(this.buildSource(parsedSource.v1().xContent(), map));
    }

    // Delegate the execution of the request to the original search action
    this.searchAction.execute(request, actionListener);

    logger.debug("{}: Coordinated search action completed", Thread.currentThread().getName());
  }

  /**
   * Wrapper around a listener that is responsible for injecting the coordinate search metadata
   * into the search response.
   */
  public static class CoordinateSearchListener implements ActionListener<SearchResponse> {

    private final ActionListener<SearchResponse> actionListener;

    private CoordinateSearchMetadata metadata;

    public CoordinateSearchListener(final ActionListener<SearchResponse> listener) {
      this.actionListener = listener;
    }

    public void setMetadata(CoordinateSearchMetadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public final void onResponse(SearchResponse response) {
      CoordinateSearchResponse r = new CoordinateSearchResponse(response, metadata);
      this.actionListener.onResponse(r);
    }

    @Override
    public final void onFailure(Throwable e) {
      this.actionListener.onFailure(e);
    }

  }

}
