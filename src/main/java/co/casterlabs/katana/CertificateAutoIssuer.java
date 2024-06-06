package co.casterlabs.katana;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Problem;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;

import co.casterlabs.katana.router.http.HttpRouterConfiguration.HttpSSLConfiguration;
import lombok.experimental.StandardException;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

// Adapted from: https://github.com/shred/acme4j/blob/master/acme4j-example/src/main/java/org/shredzone/acme4j/example/ClientTest.java
public class CertificateAutoIssuer {
    private static final String CA = System.getProperty("katana.cai.ca", "acme://letsencrypt.org");
    private static final File CA_KEY = new File("ca-user.key");
    private static final File CA_DOMAIN_KEY = new File("ca-domain.key");

    private static final int MAX_VALIDATION_ATTEMPTS = 10;

    private static final FastLogger LOGGER = Katana.getInstance().getLogger().createChild("CertificateAutoIssuer");

    public static final Object AGREE_LOCK = new Object();
    public static final Map<String, String> activeChallenges = new HashMap<>();

    private static KeyPair userKey;
    private static KeyPair domainKey;
    private static Session session;
    private static Account account;

    private HttpSSLConfiguration config;

    private static void setupIfNotAlready(HttpSSLConfiguration emailSource) throws AcmeException, IOException, InterruptedException {
        if (session != null) return;

        Security.addProvider(new BouncyCastleProvider());

        if (CA_KEY.exists()) {
            // If there is a key file, read it
            try (FileReader fr = new FileReader(CA_KEY)) {
                userKey = KeyPairUtils.readKeyPair(fr);
            }

        } else {
            // If there is none, create a new key pair and save it
            userKey = KeyPairUtils.createKeyPair();
            try (FileWriter fw = new FileWriter(CA_KEY)) {
                KeyPairUtils.writeKeyPair(userKey, fw);
            }
        }

        session = new Session(CA);
        {
            Optional<URI> tos = session.getMetadata().getTermsOfService();
            if (tos.isPresent()) {
                LOGGER.warn("By using certificate_auto_issuer, you agree to the following terms of service: %s", tos.get());
                if (!"true".equalsIgnoreCase(System.getProperty("katana.cai.agree"))) {
                    LOGGER.warn("Restart the server with '-Dkatana.cai.agree=true' if you agree to those terms.");
                    TimeUnit.SECONDS.sleep(10);
                    System.exit(1);
                    Thread.sleep(Long.MAX_VALUE); // Don't allow the server to continue starting while the VM shuts down.
                }

                LOGGER.warn("You already agreed to the terms :)");
            }
        }

        {
            AccountBuilder accountBuilder = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(userKey);

            if (emailSource.certAutoIssuer.accountEmail != null) {
                accountBuilder.addEmail(emailSource.certAutoIssuer.accountEmail);
            }

            // TODO Use the KID and HMAC if the CA uses External Account Binding
//        if (EAB_KID != null && EAB_HMAC != null) {
//            accountBuilder.withKeyIdentifier(EAB_KID, EAB_HMAC);
//        }

            account = accountBuilder.create(session);
        }

        if (CA_DOMAIN_KEY.exists()) {
            try (FileReader fr = new FileReader(CA_DOMAIN_KEY)) {
                domainKey = KeyPairUtils.readKeyPair(fr);
            }
        } else {
            KeyPair domainKeyPair = KeyPairUtils.createKeyPair(4096);
            try (FileWriter fw = new FileWriter(CA_DOMAIN_KEY)) {
                KeyPairUtils.writeKeyPair(domainKeyPair, fw);
            }
            domainKey = domainKeyPair;
        }
    }

    public CertificateAutoIssuer(HttpSSLConfiguration config) throws IOException, AcmeException, InterruptedException {
        this.config = config;
        setupIfNotAlready(config);
    }

    public synchronized void reissue(Set<String> domains) throws IssuanceException {
        if (config.certAutoIssuer.method == IssueMethod.HTTP) {
            for (String domain : domains) {
                if (domain.contains("*")) {
                    throw new IssuanceException("Cannot use HTTP validation method to issue a wildcard certificate. Switch to DNS_<provider> or remove the wildcard domain if possible.");
                }
            }
        }

        try {
            Order order = account.newOrder().domains(domains).create();

            for (Authorization auth : order.getAuthorizations()) {
                Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
                    .orElseThrow(
                        () -> new AcmeException(
                            "Found no http-01 challenge, giving up..."
                        )
                    );

                if (challenge.getStatus() != Status.VALID) {
                    // Server has told us to validate.
                    activeChallenges.put(challenge.getToken(), challenge.getAuthorization());
                    challenge.trigger();

                    pollForCompletion(order, challenge::getStatus, challenge::fetch, challenge::getError);
                }
            }

            order.execute(domainKey);
            pollForCompletion(order, order::getStatus, order::fetch, order::getError);

            Certificate certificate = order.getCertificate();
            List<X509Certificate> chain = certificate.getCertificateChain();

            new File(config.privateKeyFile).getParentFile().mkdirs();
            new File(config.certificateFile).getParentFile().mkdirs();
            new File(config.trustChainFile).getParentFile().mkdirs();

            // Save the private key
            try (FileOutputStream fos = new FileOutputStream(config.privateKeyFile)) {
                fos.write(Files.readAllBytes(CA_DOMAIN_KEY.toPath()));
            }

            // Save the public key
            try (FileOutputStream fos = new FileOutputStream(config.certificateFile)) {
                fos.write("-----BEGIN CERTIFICATE-----\n".getBytes());
                fos.write(Base64.getMimeEncoder(64, "\n".getBytes()).encode(chain.get(0).getEncoded()));
                fos.write("\n-----END CERTIFICATE-----\n".getBytes());
            }

            // Save the chain
            try (FileOutputStream fos = new FileOutputStream(config.trustChainFile)) {
                for (X509Certificate cert : chain) {
                    fos.write("-----BEGIN CERTIFICATE-----\n".getBytes());
                    fos.write(Base64.getMimeEncoder(64, "\n".getBytes()).encode(cert.getEncoded()));
                    fos.write("\n-----END CERTIFICATE-----\n".getBytes());
                }
            }

            // All done! The certificate auto-reload will read-back these files we just
            // created.
        } catch (CertificateEncodingException | AcmeException | InterruptedException | IOException e) {
            LOGGER.severe("Unable to auto-issue certificates:\n%s", e);
            throw new IssuanceException("Unable to auto-issue certificates", e);
        } finally {
            activeChallenges.clear();
        }
    }

    private static void pollForCompletion(Order order, Supplier<Status> statusProvider, Supplier_AcmeException<Optional<Instant>> retryAfterProvider, Supplier<Optional<Problem>> problemProvider) throws AcmeException, InterruptedException {
        for (int attempt = 0; true; attempt++) {
            if (attempt >= MAX_VALIDATION_ATTEMPTS) {
                throw new AcmeException("Failed to validate challenge after " + MAX_VALIDATION_ATTEMPTS + " attempts. Bailing out...");
            }

            Instant now = Instant.now();
            Instant retryAfter = retryAfterProvider.get().orElse(now.plusSeconds(3L));

            // Check the status
            switch (statusProvider.get()) {
                case VALID:
                    return;

                case INVALID:
                    throw new AcmeException(
                        "Order failed: " + problemProvider.get()
                            .map(Problem::toString)
                            .orElse("<no error provided>")
                    );

                default:
                    Thread.sleep(now.until(retryAfter, ChronoUnit.MILLIS));
                    continue; // Poll again...
            }
        }
    }

    private static interface Supplier_AcmeException<T> {

        public T get() throws AcmeException;

    }

    @StandardException
    public static class IssuanceException extends Exception {
        private static final long serialVersionUID = 3921546006277274163L;

    }

    public static enum IssueMethod {
        HTTP
    }

}
