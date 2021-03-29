package com.guzhandong.springframework.boot.elasticsearch.client;

import com.guzhandong.springframework.boot.elasticsearch.exception.impl.GetActiveClientException;
import com.guzhandong.springframework.boot.elasticsearch.pool.ElasticsearchClientPool;
import com.guzhandong.springframework.boot.elasticsearch.utils.LogUtil;
import org.apache.http.Header;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.main.MainResponse;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.IndicesClient;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;

import java.io.IOException;

/**
 * es 高级客户端连接池版本实现，完全覆盖了官方 ${@link org.elasticsearch.client.RestHighLevelClient} 的public方法.
 * <p>
 * 使用同步接口的使用方式和官方没有任何区别，同步接口在调用完成后会自动调用 {@link #releaseClient} 方法来释放client 到资源池中<br>
 * 其中异步接口完成后需要主动调用 {@link #releaseClient} 方法来释放client 到资源池中，否则将导致大量连接被占用，新的线程获取连接的时候没有可用连接。<br>
 * 在使用同步/异步API的过程中，如果当前线程正在使用client，没有释放，再一次使用API(同步/异步)的时候回主动释放当前线程持有的client资源到连接池，这个请特别注意。
 *
 */
public class RestHighLevelClient {

    private LogUtil logUtil = LogUtil.getLogger(getClass());

    private ElasticsearchClientPool elasticsearchClientPool;

    private final ThreadLocal<org.elasticsearch.client.RestHighLevelClient> threadLocal = new ThreadLocal<>();


    public RestHighLevelClient(ElasticsearchClientPool elasticsearchClientPool) {
        this.elasticsearchClientPool = elasticsearchClientPool;
    }

    private org.elasticsearch.client.RestHighLevelClient getClient()  {
        if (threadLocal.get()!=null){
            releaseClient();
        }
        try {
            org.elasticsearch.client.RestHighLevelClient restHighLevelClient = elasticsearchClientPool.borrowObject();
            threadLocal.set(restHighLevelClient);
            return restHighLevelClient;
        } catch (Exception e) {
            throw new GetActiveClientException(e);
        }

    }

    /**
     * 使用异步api接口的时候需要在使用完成之后主动调用该方法，释放连接池的连接，否则可能导致连接池无可用的新连接
     */
    public void releaseClient() {
        org.elasticsearch.client.RestHighLevelClient restHighLevelClient = threadLocal.get();
        if (restHighLevelClient!=null) {
            elasticsearchClientPool.returnObject(restHighLevelClient);
            threadLocal.remove();
        }
    }

    /**
     *
     * 执行方法，执行前从连接池获取一个连接，执行完后归还连接到连接池
     * @param call {@link Call}
     * @return {@link Object}
     */
    public Object exec(Call call){
        return exec(call,true);
    }

    /**
     *
     * 执行方法，执行前从连接池获取一个连接
     * @param call {@link Call}
     * @param releaseClient  该方法执行完成后是否释放client到资源池
     * @return {@link Object}
     */

    public Object exec(Call call,boolean releaseClient){
        org.elasticsearch.client.RestHighLevelClient restHighLevelClient = getClient();
        try {
            return call.hanl(restHighLevelClient);
        } catch (IOException e) {
            return null;
        }
        finally {
            if (releaseClient) {
                releaseClient();
            }
        }
    }

    /**
     * 执行方法，执行前从连接池获取一个连接，该方法执行完成后将释放client到资源池
     * @param call {@link VoidCall}
     */
    public void execReturnVoid(VoidCall call){
        execReturnVoid(call,true);
    }
    /**
     * 执行方法，执行前从连接池获取一个连接，执行完后归还连接到连接池
     * @param call {@link VoidCall}
     * @param releaseClient  该方法执行完成后是否释放client到资源池
     */
    public void execReturnVoid(VoidCall call,boolean releaseClient){
        org.elasticsearch.client.RestHighLevelClient restHighLevelClient = getClient();
        try {
            call.hanl(restHighLevelClient);
        } catch (IOException e) {

        }
        finally {
            if (releaseClient) {
                releaseClient();
            }
        }
    }



    /**
     * 有参数返回的执行接口
     */
    interface Call {
        public Object hanl(org.elasticsearch.client.RestHighLevelClient restHighLevelClient) throws IOException;
    }
    /**
     * 无参数返回的执行接口
     */
    interface VoidCall{
        public void hanl(org.elasticsearch.client.RestHighLevelClient restHighLevelClient) throws IOException;
    }


    /**
     * 获取低级客户端，使用完该客户端后需要主动调用 ${@link #releaseClient()} 方法释放client到资源池
     * @return
     */
    public RestClient getLowLevelClient() {
        return (RestClient)exec((r)->r.getLowLevelClient(),false);
    }

    /**
     * 获取索引操作client ,使用完该客户端后需要主动调用 ${@link #releaseClient()} 方法释放client到资源池
     * @return
     */
    public final IndicesClient indices() {
        return (IndicesClient)exec((r)->r.indices(),false);
    }

    /**
     * Executes a bulk request using the Bulk API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html">Bulk API on elastic.co</a>
     */
    public final BulkResponse bulk(BulkRequest bulkRequest, RequestOptions options) throws IOException {
        return (BulkResponse)exec((r)->r.bulk(bulkRequest,options));
    }

    /**
     * Asynchronously executes a bulk request using the Bulk API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html">Bulk API on elastic.co</a>
     */
    public final void bulkAsync(BulkRequest bulkRequest, RequestOptions options, ActionListener<BulkResponse> listener) {
        execReturnVoid((r)->r.bulkAsync(bulkRequest,options,listener),false);
    }

    /**
     * Pings the remote Elasticsearch cluster and returns true if the ping succeeded, false otherwise
     */
    public final boolean ping(RequestOptions options) throws IOException {
        return (Boolean) exec((r)->r.ping(options));
    }

    /**
     * Get the cluster info otherwise provided when sending an HTTP request to port 9200
     */
    public final MainResponse info(RequestOptions options) throws IOException {
        return (MainResponse)exec((r)->r.info(options));
    }

    /**
     * Retrieves a document by id using the Get API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html">Get API on elastic.co</a>
     */
    public final GetResponse get(GetRequest getRequest, RequestOptions options) throws IOException {
        return (GetResponse)exec((r)->r.get(getRequest,options));
    }

    /**
     * Asynchronously retrieves a document by id using the Get API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html">Get API on elastic.co</a>
     */
    public final void getAsync(GetRequest getRequest, ActionListener<GetResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.getAsync(getRequest,options,listener),false);
    }

    /**
     * Retrieves multiple documents by id using the Multi Get API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-multi-get.html">Multi Get API on elastic.co</a>
     */
    public final MultiGetResponse multiGet(MultiGetRequest multiGetRequest, RequestOptions options) throws IOException {
        return (MultiGetResponse)exec((r)->r.multiGet(multiGetRequest,options));
    }

    /**
     * Asynchronously retrieves multiple documents by id using the Multi Get API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-multi-get.html">Multi Get API on elastic.co</a>
     */
    public void multiGetAsync(MultiGetRequest multiGetRequest, ActionListener<MultiGetResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.multiGetAsync(multiGetRequest,options,listener),false);
    }

    /**
     * Checks for the existence of a document. Returns true if it exists, false otherwise
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html">Get API on elastic.co</a>
     */
    public final boolean exists(GetRequest getRequest, RequestOptions options) throws IOException {
        return (Boolean) exec((r)->r.exists(getRequest,options));
    }

    /**
     * Asynchronously checks for the existence of a document. Returns true if it exists, false otherwise
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html">Get API on elastic.co</a>
     */
    public final void existsAsync(GetRequest getRequest, ActionListener<Boolean> listener, RequestOptions options) {
        execReturnVoid((r)->r.existsAsync(getRequest,options,listener),false);
    }

    /**
     * Index a document using the Index API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-index_.html">Index API on elastic.co</a>
     */
    public final IndexResponse index(IndexRequest indexRequest, RequestOptions options) throws IOException {
        return (IndexResponse)exec((r)->r.index(indexRequest,options));
    }

    /**
     * Asynchronously index a document using the Index API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-index_.html">Index API on elastic.co</a>
     */
    public final void indexAsync(IndexRequest indexRequest, ActionListener<IndexResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.indexAsync(indexRequest,options,listener),false);
    }

    /**
     * Updates a document using the Update API
     * <p>
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html">Update API on elastic.co</a>
     */
    public final UpdateResponse update(UpdateRequest updateRequest, RequestOptions options) throws IOException {
        return (UpdateResponse)exec((r)->r.update(updateRequest,options));
    }

    /**
     * Asynchronously updates a document using the Update API
     * <p>
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html">Update API on elastic.co</a>
     */
    public final void updateAsync(UpdateRequest updateRequest, ActionListener<UpdateResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.update(updateRequest,options),false);
    }

    /**
     * Deletes a document by id using the Delete API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-delete.html">Delete API on elastic.co</a>
     */
    public final DeleteResponse delete(DeleteRequest deleteRequest, RequestOptions options) throws IOException {
        return (DeleteResponse)exec((r)->r.delete(deleteRequest,options));
    }

    /**
     * Asynchronously deletes a document by id using the Delete API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-delete.html">Delete API on elastic.co</a>
     */
    public final void deleteAsync(DeleteRequest deleteRequest, ActionListener<DeleteResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.deleteAsync(deleteRequest,options,listener),false);
    }

    /**
     * Executes a search using the Search API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-search.html">Search API on elastic.co</a>
     */
    public final SearchResponse search(SearchRequest searchRequest, RequestOptions options) throws IOException {
        return (SearchResponse)exec((r)->r.search(searchRequest,options));
    }

    /**
     * Asynchronously executes a search using the Search API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-search.html">Search API on elastic.co</a>
     */
    public final void searchAsync(SearchRequest searchRequest, ActionListener<SearchResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.searchAsync(searchRequest,options,listener),false);
    }

    /**
     * Executes a multi search using the msearch API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-multi-search.html">Multi search API on
     * elastic.co</a>
     */
    public final MultiSearchResponse multiSearch(MultiSearchRequest multiSearchRequest, RequestOptions options) throws IOException {
        return (MultiSearchResponse)exec((r)->r.multiSearch(multiSearchRequest,options));
    }

    /**
     * Asynchronously executes a multi search using the msearch API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-multi-search.html">Multi search API on
     * elastic.co</a>
     */
    public final void multiSearchAsync(MultiSearchRequest searchRequest, ActionListener<MultiSearchResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.multiSearchAsync(searchRequest,options,listener),false);
    }

    /**
     * Executes a search using the Search Scroll API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-scroll.html">Search Scroll
     * API on elastic.co</a>
     */
    public final SearchResponse searchScroll(SearchScrollRequest searchScrollRequest, RequestOptions options) throws IOException {
        return (SearchResponse)exec((r)->r.searchScroll(searchScrollRequest,options));
    }

    /**
     * Asynchronously executes a search using the Search Scroll API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-scroll.html">Search Scroll
     * API on elastic.co</a>
     */
    public final void searchScrollAsync(SearchScrollRequest searchScrollRequest,
                                        ActionListener<SearchResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.searchScrollAsync(searchScrollRequest,options,listener),false);
    }

    /**
     * Clears one or more scroll ids using the Clear Scroll API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-scroll.html#_clear_scroll_api">
     * Clear Scroll API on elastic.co</a>
     */
    public final ClearScrollResponse clearScroll(ClearScrollRequest clearScrollRequest, RequestOptions options) throws IOException {
        return (ClearScrollResponse)exec((r)->r.clearScroll(clearScrollRequest,options));
    }

    /**
     * Asynchronously clears one or more scroll ids using the Clear Scroll API
     *
     * See <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-scroll.html#_clear_scroll_api">
     * Clear Scroll API on elastic.co</a>
     */
    public final void clearScrollAsync(ClearScrollRequest clearScrollRequest,
                                       ActionListener<ClearScrollResponse> listener, RequestOptions options) {
        execReturnVoid((r)->r.clearScrollAsync(clearScrollRequest,options,listener),false);
    }



}
