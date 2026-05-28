package co.hotwax.auth;

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import groovy.transform.CompileStatic
import org.eclipse.jetty.util.StringUtil
import org.moqui.context.ExecutionContext
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

@CompileStatic
public class JWTManager {
    protected final static Logger logger = LoggerFactory.getLogger(JWTManager.class);

    public static Map<String, Object> validateToken(String jwtToken, ExecutionContext ec) {
        try {
            return validateToken(jwtToken, getJWTKey(ec, null));
        } catch (JWTVerificationException e) {
            ec.message.addError(e.getMessage())
        }
        return null;
    }

    public static Map<String, Object> validateToken(String jwtToken, String key) throws JWTVerificationException {
        return validateToken(jwtToken, key, (String) SystemBinding.getPropOrEnv("ofbiz.instance.name"), null)
    }
    public static Map<String, Object> validateToken(String jwtToken, String key, String algorithmType) throws JWTVerificationException {
        return validateToken(jwtToken, key, (String) SystemBinding.getPropOrEnv("ofbiz.instance.name"), algorithmType)
    }
    public static Map<String, Object> validateToken(String jwtToken, String key, String issuer, String algorithmType) throws JWTVerificationException {
        Map<String, Object> result = new HashMap<>();
        if (!(jwtToken || key)) {
            String msg = "JWT token or key can not be empty.";
            logger.error(msg);
            throw new JWTVerificationException(msg);
        }
        try {
            Algorithm algo;
            if ('HmacSHA256' == algorithmType) {
                algo = Algorithm.HMAC256(key);
            } else {
                algo = Algorithm.HMAC512(key);
            }
            // Added 60-second leeway to acceptIssuedAt to tolerate clock skew.
            // This allows tokens with a future 'iat' (issued-at) time up to 60s ahead,
            // which may happen when JWTs are generated on a different system with slightly advanced time.
            JWTVerifier verifier = JWT.require(algo)
                    .withIssuer(issuer)
                    .acceptIssuedAt(60)
                    .build();
            DecodedJWT jwt = verifier.verify(jwtToken);
            Map<String, Claim> claims = jwt.getClaims();
            //OK, we can trust this JWT
            for (Map.Entry<String, Claim> entry : claims.entrySet()) {
                result.put(entry.getKey(), entry.getValue().asString());
            }
            return result;
        } catch (Exception e) {
            // signature not valid or token expired
            logger.error(e.getMessage());
            throw new JWTVerificationException(e.getMessage());
        }
    }

    public static String getJWTKey(ExecutionContext ec, String salt) {
        Cache jwtKey = ec.cache.getCache("jwt.token");
        String key = jwtKey.get("jwt.token.key")
        if (key == null || key.isEmpty()) {
            File jwtKeyFile = new File(ec.getFactory().getRuntimePath() + "/conf/jwtKey.txt")
            if (jwtKeyFile) {
                key = jwtKeyFile.getText().trim();
                jwtKey.put("jwt.token.key", key);
            } else {
                logger.error("JWT key file not found at location " + (ec.getFactory().getRuntimePath() + "/conf/jwtKey.txt"))
            }
        }
        if (salt != null) {
            return StringUtil.toHexString(salt.getBytes()) + key;
        }
        return key;
    }

    public static String createJwt(ExecutionContext ec, Map<String, String> claims) {
        return createJwt(ec, claims, null);
    }
    public static String createJwt(ExecutionContext ec, Map<String, String> claims, String keySalt) {
        int defaultExpireTime = Integer.valueOf(System.getProperty("jwt.default.expireTime")?:"300");
        return createJwt(ec, claims, keySalt, defaultExpireTime);
    }

    public static String createJwt(ExecutionContext ec, Map<String, String> claims, String keySalt, int expireTime) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ec.user.nowTimestamp.getTime());
        cal.add(Calendar.SECOND, expireTime);

        return createJwt(ec, claims, keySalt, cal.getTime());
    }

    public static String createJwt(ExecutionContext ec, Map<String, String> claims, String keySalt, Date expiresAt) {
        JWTCreator.Builder builder = JWT.create()
                .withIssuedAt(ec.user.nowTimestamp)
                .withExpiresAt(expiresAt)
                .withIssuer(SystemBinding.getPropOrEnv("ofbiz.instance.name"));
        for (Map.Entry<String, String> entry : claims.entrySet()) {
            builder.withClaim(entry.getKey(), entry.getValue());
        }

        return builder.sign(Algorithm.HMAC512(JWTManager.getJWTKey(ec, keySalt)));
    }
}
