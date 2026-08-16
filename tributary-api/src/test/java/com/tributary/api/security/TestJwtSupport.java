package com.tributary.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;

/**
 * CV-09's own tooling: real tokens, real signatures, minted with a real keypair generated fresh
 * for the test JVM (never a hardcoded literal — SRS 5.3's secrets discipline applied to test
 * material too, not just production credentials).
 */
public final class TestJwtSupport {

  private TestJwtSupport() {}

  public static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String publicKeyPem(RSAPublicKey publicKey) {
    String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
    for (int i = 0; i < base64.length(); i += 64) {
      pem.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
    }
    pem.append("-----END PUBLIC KEY-----\n");
    return pem.toString();
  }

  /** A real, correctly RS256-signed token — what a legitimate caller presents. */
  public static String validToken(RSAPrivateKey privateKey, String subject, String role) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .claim("role", role)
              .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(privateKey));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * CV-09, half 1: {@code alg: none}, no signature at all. Built by hand (base64url header +
   * payload + an empty third segment) rather than through a JWT library's own API, which
   * typically refuses to construct this on purpose — this is exactly the forged shape a real
   * attacker would send, not a library-mediated approximation of it.
   */
  public static String algNoneToken(String subject, String role) {
    String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
    String payload = "{\"sub\":\"" + subject + "\",\"role\":\"" + role + "\"}";
    return base64Url(header) + "." + base64Url(payload) + ".";
  }

  /**
   * CV-09, half 2: the classic RS256-to-HS256 key-confusion attack — an attacker who only ever
   * had the PUBLIC key signs an HS256 token using its raw bytes as an HMAC secret, gambling that a
   * naive verifier configured to "accept whatever alg the token claims" will use the same public
   * key material to verify an HMAC, which unlike RSA verification is symmetric and succeeds.
   * {@link SecurityConfig#jwtDecoder} defeats this by fixing the accepted algorithm to RS256
   * outright — this token must be rejected before HMAC verification is ever attempted.
   */
  public static String hs256TokenSignedWithPublicKeyBytes(RSAPublicKey publicKey, String subject, String role) {
    try {
      JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).claim("role", role).build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(publicKey.getEncoded()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String base64Url(String value) {
    return Base64URL.encode(value.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
