public class JdkSslContext extends SslContext {
	static {
        SSLContext context;
        try {
            context = SSLContext.getInstance(PROTOCOL);
			// 密钥传null?
            context.init(null, null, null);
        } catch (Exception e) {
            throw new Error("failed to initialize the default SSL context", e);
        }

        DEFAULT_PROVIDER = context.getProvider();

        SSLEngine engine = context.createSSLEngine();
        DEFAULT_PROTOCOLS = defaultProtocols(engine);

        SUPPORTED_CIPHERS = Collections.unmodifiableSet(supportedCiphers(engine));
        DEFAULT_CIPHERS = Collections.unmodifiableList(defaultCiphers(engine, SUPPORTED_CIPHERS));

        List<String> ciphersNonTLSv13 = new ArrayList<String>(DEFAULT_CIPHERS);
        ciphersNonTLSv13.removeAll(Arrays.asList(SslUtils.DEFAULT_TLSV13_CIPHER_SUITES));
        DEFAULT_CIPHERS_NON_TLSV13 = Collections.unmodifiableList(ciphersNonTLSv13);

        Set<String> suppertedCiphersNonTLSv13 = new LinkedHashSet<String>(SUPPORTED_CIPHERS);
        suppertedCiphersNonTLSv13.removeAll(Arrays.asList(SslUtils.DEFAULT_TLSV13_CIPHER_SUITES));
        SUPPORTED_CIPHERS_NON_TLSV13 = Collections.unmodifiableSet(suppertedCiphersNonTLSv13);
    }
}

final class TcpClientSecure extends TcpClientOperator {
	static {
		// 默认的SslProvider没有配置密钥
		SslProvider sslProvider =
					SslProvider.builder()
					           .sslContext(SslContextBuilder.forClient())
					           .defaultConfiguration(SslProvider.DefaultConfigurationType.TCP)
					           .build();
		DEFAULT_CLIENT_PROVIDER = sslProvider;
	}
}