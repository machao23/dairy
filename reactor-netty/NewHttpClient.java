// HttpClient在新版本里是一个抽象类，作为静态工具类的角色存在
public abstract class HttpClient {
	// 创建一个http客户端
	public static HttpClient create(ConnectionProvider connectionProvider) {
		return new HttpClientConnect(TcpClient.create(connectionProvider).port(80));
	}
}