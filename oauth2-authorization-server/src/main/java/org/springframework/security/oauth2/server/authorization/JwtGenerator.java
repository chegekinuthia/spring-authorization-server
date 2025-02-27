/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.function.Consumer;

import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JoseHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * An {@link OAuth2TokenGenerator} that generates a {@link Jwt}
 * used for an {@link OAuth2AccessToken} or {@link OidcIdToken}.
 *
 * @author Joe Grandja
 * @since 0.2.3
 * @see OAuth2TokenGenerator
 * @see Jwt
 * @see JwtEncoder
 * @see OAuth2TokenCustomizer
 * @see JwtEncodingContext
 * @see OAuth2AccessToken
 * @see OidcIdToken
 */
public final class JwtGenerator implements OAuth2TokenGenerator<Jwt> {
	private final JwtEncoder jwtEncoder;
	private OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer;

	/**
	 * Constructs a {@code JwtGenerator} using the provided parameters.
	 *
	 * @param jwtEncoder the jwt encoder
	 */
	public JwtGenerator(JwtEncoder jwtEncoder) {
		Assert.notNull(jwtEncoder, "jwtEncoder cannot be null");
		this.jwtEncoder = jwtEncoder;
	}

	@Nullable
	@Override
	public Jwt generate(OAuth2TokenContext context) {
		if (context.getTokenType() == null ||
				(!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType()) &&
						!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue()))) {
			return null;
		}

		String issuer = null;
		if (context.getProviderContext() != null) {
			issuer = context.getProviderContext().getIssuer();
		}
		RegisteredClient registeredClient = context.getRegisteredClient();

		Instant issuedAt = Instant.now();
		Instant expiresAt;
		if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
			// TODO Allow configuration for ID Token time-to-live
			expiresAt = issuedAt.plus(30, ChronoUnit.MINUTES);
		} else {
			expiresAt = issuedAt.plus(registeredClient.getTokenSettings().getAccessTokenTimeToLive());
		}

		// @formatter:off
		JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder();
		if (StringUtils.hasText(issuer)) {
			claimsBuilder.issuer(issuer);
		}
		claimsBuilder
				.subject(context.getPrincipal().getName())
				.audience(Collections.singletonList(registeredClient.getClientId()))
				.issuedAt(issuedAt)
				.expiresAt(expiresAt);
		if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
			claimsBuilder.notBefore(issuedAt);
			if (!CollectionUtils.isEmpty(context.getAuthorizedScopes())) {
				claimsBuilder.claim(OAuth2ParameterNames.SCOPE, context.getAuthorizedScopes());
			}
		} else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
			claimsBuilder.claim(IdTokenClaimNames.AZP, registeredClient.getClientId());
			if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
				OAuth2AuthorizationRequest authorizationRequest = context.getAuthorization().getAttribute(
						OAuth2AuthorizationRequest.class.getName());
				String nonce = (String) authorizationRequest.getAdditionalParameters().get(OidcParameterNames.NONCE);
				if (StringUtils.hasText(nonce)) {
					claimsBuilder.claim(IdTokenClaimNames.NONCE, nonce);
				}
			}
			// TODO Add 'auth_time' claim
		}
		// @formatter:on

		JoseHeader.Builder headersBuilder = JoseHeader.withAlgorithm(SignatureAlgorithm.RS256);

		if (this.jwtCustomizer != null) {
			// @formatter:off
			JwtEncodingContext.Builder jwtContextBuilder = JwtEncodingContext.with(headersBuilder, claimsBuilder)
					.registeredClient(context.getRegisteredClient())
					.principal(context.getPrincipal())
					.providerContext(context.getProviderContext())
					.authorizedScopes(context.getAuthorizedScopes())
					.tokenType(context.getTokenType())
					.authorizationGrantType(context.getAuthorizationGrantType());
			if (context.getAuthorization() != null) {
				jwtContextBuilder.authorization(context.getAuthorization());
			}
			if (context.getAuthorizationGrant() != null) {
				jwtContextBuilder.authorizationGrant(context.getAuthorizationGrant());
			}
			// @formatter:on

			JwtEncodingContext jwtContext = jwtContextBuilder.build();
			this.jwtCustomizer.customize(jwtContext);
		}

		JoseHeader headers = headersBuilder.build();
		JwtClaimsSet claims = claimsBuilder.build();

		Jwt jwt = this.jwtEncoder.encode(headers, claims);

		return jwt;
	}

	/**
	 * Sets the {@link OAuth2TokenCustomizer} that customizes the
	 * {@link JwtEncodingContext.Builder#headers(Consumer) headers} and/or
	 * {@link JwtEncodingContext.Builder#claims(Consumer) claims} for the generated {@link Jwt}.
	 *
	 * @param jwtCustomizer the {@link OAuth2TokenCustomizer} that customizes the headers and/or claims for the generated {@code Jwt}
	 */
	public void setJwtCustomizer(OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer) {
		Assert.notNull(jwtCustomizer, "jwtCustomizer cannot be null");
		this.jwtCustomizer = jwtCustomizer;
	}

}
