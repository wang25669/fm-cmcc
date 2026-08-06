package com.fmplayer.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 包一层 SSLSocketFactory，强制握手只用 TLS 1.2。
 * 内部实际的信任逻辑（是否校验证书）由外部传入的 delegate 决定，
 * 这个类只负责协议版本这一件事。
 */
public class TlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory d;

    public TlsSocketFactory(SSLSocketFactory delegate) {
        this.d = delegate;
    }

    @Override public String[] getDefaultCipherSuites()   { return d.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites()  { return d.getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return e(d.createSocket(s,h,p,a)); }
    @Override public Socket createSocket(String h, int p) throws IOException { return e(d.createSocket(h,p)); }
    @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return e(d.createSocket(h,p,l,lp)); }
    @Override public Socket createSocket(InetAddress h, int p) throws IOException { return e(d.createSocket(h,p)); }
    @Override public Socket createSocket(InetAddress a, int p, InetAddress l, int lp) throws IOException { return e(d.createSocket(a,p,l,lp)); }

    private Socket e(Socket s) {
        if (s instanceof SSLSocket) ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2"});
        return s;
    }
}
