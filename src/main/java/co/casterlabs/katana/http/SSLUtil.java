package co.casterlabs.katana.http;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.util.Base64;
import java.util.Enumeration;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.xml.bind.DatatypeConverter;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;

public class SSLUtil {
    private static final char[] TEMPORARY_KEY_PASSWORD = String.format("%.6f", Math.random()).toCharArray();

    private static final String CERT_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String CERT_END = "-----END CERTIFICATE-----";
    private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
    private static final String RSA_PRIVATE_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String RSA_PRIVATE_END = "-----END RSA PRIVATE KEY-----";

    public static SSLServerSocketFactory getSocketFactoryPEM(File certificate, File privateKey, File chain) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");

        byte[] certBytes = parseDERFromPEM(Files.readAllBytes(certificate.toPath()), CERT_BEGIN, CERT_END);
        byte[] chainBytes = parseDERFromPEM(Files.readAllBytes(chain.toPath()), CERT_BEGIN, CERT_END);

        Certificate chainCert = generateCertificateFromDER(chainBytes);
        Certificate cert = generateCertificateFromDER(certBytes);
        PrivateKey key = parseDERFromPrivate(Files.readAllBytes(privateKey.toPath()));

        KeyStore keystore = KeyStore.getInstance("JKS");

        keystore.load(null);
        keystore.setCertificateEntry("ca-cert", chainCert);
        keystore.setCertificateEntry("cert-alias", cert);
        keystore.setKeyEntry("key-alias", key, TEMPORARY_KEY_PASSWORD, new Certificate[] {
                cert
        });

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");

        kmf.init(keystore, TEMPORARY_KEY_PASSWORD);

        KeyManager[] km = kmf.getKeyManagers();

        context.init(km, null, null);

        return context.getServerSocketFactory();
    }

    private static byte[] parseDERFromPEM(byte[] pem, String beginDelimiter, String endDelimiter) {
        String data = new String(pem);
        String[] tokens = data.split(beginDelimiter);

        tokens = tokens[1].split(endDelimiter);

        return DatatypeConverter.parseBase64Binary(tokens[0]);
    }

    @SuppressWarnings("unused") // Alot of data is skipped
    private static PrivateKey parseDERFromPrivate(byte[] pem) throws Exception {
        String data = new String(pem);

        if (data.contains(PRIVATE_BEGIN)) {
            data = data.replace(PRIVATE_BEGIN, "").replace(PRIVATE_END, "").replaceAll("\\s", "");

            byte[] b64 = DatatypeConverter.parseBase64Binary(data);

            return generatePrivateKeyFromDER(b64);
        } else {
            data = data.replace(RSA_PRIVATE_BEGIN, "").replace(RSA_PRIVATE_END, "").replaceAll("\\s", "");

            byte[] encodedPrivateKey = Base64.getDecoder().decode(data);
            ASN1Sequence primitive = (ASN1Sequence) ASN1Sequence.fromByteArray(encodedPrivateKey);
            Enumeration<?> e = primitive.getObjects();
            BigInteger v = ((ASN1Integer) e.nextElement()).getValue();

            int version = v.intValue();

            if (version != 0 && version != 1) {
                throw new IllegalArgumentException("wrong version for RSA private key");
            }

            BigInteger modulus = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger publicExponent = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger privateExponent = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger prime1 = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger prime2 = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger exponent1 = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger exponent2 = ((ASN1Integer) e.nextElement()).getValue();
            BigInteger coefficient = ((ASN1Integer) e.nextElement()).getValue();

            RSAPrivateKeySpec spec = new RSAPrivateKeySpec(modulus, privateExponent);
            KeyFactory kf = KeyFactory.getInstance("RSA");

            return kf.generatePrivate(spec);
        }
    }

    private static PrivateKey generatePrivateKeyFromDER(byte[] keyBytes) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory factory = KeyFactory.getInstance("RSA");

        return factory.generatePrivate(spec);
    }

    private static X509Certificate generateCertificateFromDER(byte[] certBytes) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");

        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certBytes));
    }

}
