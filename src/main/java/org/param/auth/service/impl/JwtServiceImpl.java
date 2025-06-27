/*
 * Copyright (c) 2024 PARAM SOFT. All rights reserved.
 * 
 * This software and its documentation (the "Software") are confidential and proprietary to PARAM SOFT.
 * The Software is protected by copyright, trade secret, and other intellectual property laws. 
 * Unauthorized use, reproduction, modification, distribution, or disclosure of the Software, 
 * in whole or in part, is strictly prohibited without prior written consent from PARAM SOFT.
 * The Software is provided "as-is" without any express or implied warranty of any kind, 
 * including but not limited to the warranties of merchant ability, fitness for a particular purpose, 
 * or non infringement. You may use the Software only in accordance with the terms of 
 * the applicable license agreement. 
 *
 * For more information, including licensing inquiries or support, 
 * please contact: PARAM SOFT - https://www.paramsoft.org
 */
package org.param.auth.service.impl;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.param.auth.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: PARAMESHWARAN PV
 * Date: 27-Jun-2025 : 1:13:51 AM
 * Since: 1.0.0
 * @See #
 */

@Slf4j
@Service
public class JwtServiceImpl implements JwtService {

	@Value("${security.jwt.access-token.expiration-ms}")
	private long accessTokenValidity;

	@Value("${security.jwt.refresh-token.expiration-ms}")
	private long refreshTokenValidity;

	private PrivateKey privateKey;

	// @formatter:off

	@PostConstruct
	public void init() {
		try {
			 // Load the private key file from resources
            ClassPathResource resource = new ClassPathResource("private_key.pem");
            InputStream inputStream = resource.getInputStream();
            String privateKeyPem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

			String privateKeyContent = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
													.replace("-----END PRIVATE KEY-----", "")
													.replaceAll("\\s+", "");

			byte[] keyBytes = Base64.getDecoder()
									.decode(privateKeyContent);
			
			PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			
			this.privateKey = keyFactory.generatePrivate(keySpec);
		} catch (Exception e) {
			log.error("Failed to load RSA private key", e);
			throw new IllegalStateException("Invalid RSA private key configuration", e);
		}
	}

	@Override
	public String generateAccessToken(String username, Set<String> roles) {
		try {
			return Jwts.builder()
					   .setSubject(username)
					   .claim("roles", roles)
					   .setIssuedAt(new Date())
					   .setExpiration(new Date(System.currentTimeMillis() + accessTokenValidity))
					   .signWith(privateKey, SignatureAlgorithm.RS256)
					   .compact();
		} catch (Exception e) {
			throw new RuntimeException("Token generation failed: " + e.getMessage());
		}

	}

	@Override
	public String generateRefreshToken(String username) {
		try {
			return Jwts.builder()
					   .setSubject(username)
					   .setIssuedAt(new Date())
					   .setExpiration(new Date(System.currentTimeMillis() + refreshTokenValidity))
					   .signWith(privateKey, SignatureAlgorithm.RS256)
					   .compact();
		} catch (Exception e) {
			throw new RuntimeException("Token generation failed: " + e.getMessage());
		}

	}

	@Override
	public boolean isTokenValid(String token) {
		try {
			Claims claims = Jwts.parserBuilder()
								.setSigningKey(privateKey)
								.build()
								.parseClaimsJws(token)
								.getBody();

			return claims.getExpiration()
						 .after(new Date());

		} catch (JwtException | IllegalArgumentException ex) {
			return false;
		}
	}

	@Override
	public String extractUsername(String token) {
		return extractClaims(token).getSubject();
	}

	@Override
	public Set<String> extractRoles(String token) {
		Claims claims = extractClaims(token);
		Object roles = claims.get("roles");

		if (roles instanceof List<?>) {
			return ((List<?>) roles).stream()
									.map(String::valueOf)
									.collect(Collectors.toSet());
		}

		return Collections.emptySet();
	}

	@Override
	public Claims extractClaims(String token) {
		return Jwts.parserBuilder()
				   .setSigningKey(privateKey)
				   .build()
				   .parseClaimsJws(token)
				   .getBody();
	}

	@Override
	public Long getExpriesIn() {
		return accessTokenValidity;
	}
	
	// @formatter:on

}
