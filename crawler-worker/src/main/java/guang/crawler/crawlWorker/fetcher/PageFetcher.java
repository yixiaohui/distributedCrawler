package guang.crawler.crawlWorker.fetcher;

import guang.crawler.commons.WebURL;
import guang.crawler.crawlWorker.WorkerConfig;
import guang.crawler.crawlWorker.url.URLCanonicalizer;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParamBean;
import org.apache.http.protocol.HttpContext;
import org.apache.log4j.Logger;

/**
 * 页面抓取器,从网上抓取指定URL所对应的页面
 *
 * @author yang
 */
public class PageFetcher {
	/**
	 * GZip格式数据信息的转化
	 *
	 * @author sun
	 *
	 */
	private static class GzipDecompressingEntity extends HttpEntityWrapper {
		
		public GzipDecompressingEntity(final HttpEntity entity) {
			super(entity);
		}
		
		@Override
		public InputStream getContent() throws IOException,
		        IllegalStateException {
			
			// the wrapped entity's getContent() decides about repeatability
			InputStream wrappedin = this.wrappedEntity.getContent();
			return new GZIPInputStream(wrappedin);
		}
		
		@Override
		public long getContentLength() {
			return -1;
		}
		
	}
	
	protected static final Logger	         logger	                 = Logger.getLogger(PageFetcher.class);
	
	/**
	 * 连接管理器.用来控制当前主机的连接情况
	 */
	protected PoolingClientConnectionManager	connectionManager;
	/**
	 * HTTP连接客户端
	 */
	protected DefaultHttpClient	             httpClient;
	/**
	 * 锁
	 */
	protected final Object	                 mutex	                 = new Object();
	/**
	 * 用来清理空闲连接的线程
	 */
	protected IdleConnectionMonitorThread	 connectionMonitorThread	= null;
	
	public PageFetcher() {
		
		WorkerConfig config = WorkerConfig.me();
		// 创建连接管理器
		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", 80,
		        PlainSocketFactory.getSocketFactory()));
		
		if (config.isIncludeHttpsPages()) {
			schemeRegistry.register(new Scheme("https", 443,
			        SSLSocketFactory.getSocketFactory()));
		}
		
		this.connectionManager = new PoolingClientConnectionManager(
		        schemeRegistry);
		this.connectionManager.setMaxTotal(config.getMaxTotalConnections());
		this.connectionManager.setDefaultMaxPerRoute(config.getMaxConnectionsPerHost());
		// 创建HTTP连接客户端
		HttpParams params = new BasicHttpParams();
		HttpProtocolParamBean paramsBean = new HttpProtocolParamBean(params);
		paramsBean.setVersion(HttpVersion.HTTP_1_1);
		paramsBean.setContentCharset("UTF-8");
		paramsBean.setUseExpectContinue(false);
		params.setParameter(ClientPNames.COOKIE_POLICY,
		                    CookiePolicy.BROWSER_COMPATIBILITY);
		params.setParameter(CoreProtocolPNames.USER_AGENT,
		                    config.getUserAgentString());
		params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT,
		                       config.getSocketTimeout());
		params.setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT,
		                       config.getConnectionTimeout());
		params.setBooleanParameter("http.protocol.handle-redirects",
		                           config.isFollowRedirects());
		this.httpClient = new DefaultHttpClient(this.connectionManager, params);
		if (config.getProxyHost() != null) {
			
			if (config.getProxyUsername() != null) {
				this.httpClient.getCredentialsProvider()
				               .setCredentials(new AuthScope(
				                                       config.getProxyHost(),
				                                       config.getProxyPort()),
				                               new UsernamePasswordCredentials(
				                                       config.getProxyUsername(),
				                                       config.getProxyPassword()));
			}
			
			HttpHost proxy = new HttpHost(config.getProxyHost(),
			        config.getProxyPort());
			this.httpClient.getParams()
			               .setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
		}
		
		this.httpClient.addResponseInterceptor(new HttpResponseInterceptor() {
			
			@Override
			public void process(final HttpResponse response,
			        final HttpContext context) throws HttpException,
			        IOException {
				HttpEntity entity = response.getEntity();
				Header contentEncoding = entity.getContentEncoding();
				if (contentEncoding != null) {
					HeaderElement[] codecs = contentEncoding.getElements();
					for (HeaderElement codec : codecs) {
						if (codec.getName()
						         .equalsIgnoreCase("gzip")) {
							response.setEntity(new GzipDecompressingEntity(
							        response.getEntity()));
							return;
						}
					}
				}
			}
			
		});
		
		if (this.connectionMonitorThread == null) {
			this.connectionMonitorThread = new IdleConnectionMonitorThread(
			        this.connectionManager);
		}
		this.connectionMonitorThread.start();
		
	}

	/**
	 * 下载数据
	 *
	 * @param webUrl
	 * @return
	 */
	public PageFetchResult fetchData(final WebURL webUrl) {
		PageFetchResult fetchResult = new PageFetchResult();
		String toFetchURL = webUrl.getURL();
		HttpGet get = null;
		try {
			get = new HttpGet(toFetchURL);
			get.addHeader("Accept-Encoding", "gzip");
			HttpResponse response = this.httpClient.execute(get);
			// 获取请求得到的结果数据
			fetchResult.setEntity(response.getEntity());
			fetchResult.setResponseHeaders(response.getAllHeaders());
			int statusCode = response.getStatusLine()
			                         .getStatusCode();
			// 如果遇到的是重定向，那么就设置重定向URL。
			if (statusCode != HttpStatus.SC_OK) {
				if (statusCode != HttpStatus.SC_NOT_FOUND) {
					if ((statusCode == HttpStatus.SC_MOVED_PERMANENTLY)
					        || (statusCode == HttpStatus.SC_MOVED_TEMPORARILY)) {
						Header header = response.getFirstHeader("Location");
						if (header != null) {
							String movedToUrl = header.getValue();
							movedToUrl = URLCanonicalizer.getCanonicalURL(movedToUrl,
							                                              toFetchURL);
							fetchResult.setMovedToUrl(movedToUrl);
						}
						fetchResult.setStatusCode(statusCode);
						return fetchResult;
					}
					PageFetcher.logger.info("Failed: "
					        + response.getStatusLine()
					                  .toString() + ", while fetching "
					        + toFetchURL);
				}
				fetchResult.setStatusCode(response.getStatusLine()
				                                  .getStatusCode());
				return fetchResult;
			} else {
				fetchResult.setFetchedUrl(toFetchURL);
				String uri = get.getURI()
				                .toString();
				// 有可能获得的结果是转发之后的，那么将fetchedUrl设置成实际爬取的URL。
				if (!uri.equals(toFetchURL)) {
					if (!URLCanonicalizer.getCanonicalURL(uri)
					                     .equals(toFetchURL)) {
						fetchResult.setFetchedUrl(uri);
					}
				}
				
				// 如果爬取的页面有内容，检测一下内容是否过大了。
				if (fetchResult.getEntity() != null) {
					long size = fetchResult.getEntity()
					                       .getContentLength();
					if (size == -1) {
						Header length = response.getLastHeader("Content-Length");
						if (length == null) {
							length = response.getLastHeader("Content-length");
						}
						if (length != null) {
							size = Integer.parseInt(length.getValue());
						} else {
							size = -1;
						}
					}
					if (size > WorkerConfig.me()
					                       .getMaxDownloadSize()) {
						fetchResult.setStatusCode(CustomFetchStatus.PageTooBig);
						get.abort();
						return fetchResult;
					}
					
					fetchResult.setStatusCode(HttpStatus.SC_OK);
					return fetchResult;
					
				}
				
			}
			get.abort();
		} catch (IOException e) {
			PageFetcher.logger.error("Fatal transport error: " + e.getMessage()
			        + " while fetching " + toFetchURL + " (link found in doc #"
			        + webUrl.getParentDocid() + ")");
			fetchResult.setStatusCode(CustomFetchStatus.FatalTransportError);
			return fetchResult;
		} catch (IllegalStateException e) {
			// ignoring exceptions that occur because of not registering https
			// and other schemes
		} catch (Exception e) {
			if (e.getMessage() == null) {
				PageFetcher.logger.error("Error while fetching "
				        + webUrl.getURL());
			} else {
				PageFetcher.logger.error(e.getMessage() + " while fetching "
				        + webUrl.getURL());
			}
		} finally {
			try {
				if ((fetchResult.getEntity() == null) && (get != null)) {
					get.abort();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		fetchResult.setStatusCode(CustomFetchStatus.UnknownError);
		return fetchResult;
	}
	
	public HttpClient getHttpClient() {
		return this.httpClient;
	}
	
	/**
	 * 关闭连接管理器
	 */
	public synchronized void shutDown() {
		if (this.connectionMonitorThread != null) {
			this.connectionManager.shutdown();
			this.connectionMonitorThread.shutdown();
		}
	}
}
