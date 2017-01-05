/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2016 ForgeRock AS.
 */

package org.forgerock.openidm.security.impl;

import static org.forgerock.json.resource.Responses.newResourceResponse;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.forgerock.api.annotations.Action;
import org.forgerock.api.annotations.Actions;
import org.forgerock.api.annotations.Handler;
import org.forgerock.api.annotations.Operation;
import org.forgerock.api.annotations.Read;
import org.forgerock.api.annotations.Schema;
import org.forgerock.api.annotations.SingletonProvider;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.ConflictException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Responses;
import org.forgerock.json.resource.SingletonResourceProvider;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.keystore.KeyStoreManagementService;
import org.forgerock.openidm.keystore.KeyStoreService;
import org.forgerock.openidm.repo.RepositoryService;
import org.forgerock.openidm.security.impl.api.CertificateResource;
import org.forgerock.openidm.security.impl.api.GenerateCertRequestAction;
import org.forgerock.openidm.security.impl.api.GenerateCsrRequestAction;
import org.forgerock.openidm.security.impl.api.GenerateCsrResponseAction;
import org.forgerock.openidm.security.impl.api.KeyStoreResource;
import org.forgerock.openidm.util.CertUtil;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Services requests on a specific keystore.
 */
@SingletonProvider(value = @Handler(
        id = "keystoreResourceProvider:0",
        title = "Security",
        description = "Allows generation of certificates, generating certificate signing requests, and reading the " +
                "alias in the keystore or truststore.",
        mvccSupported = false,
        resourceSchema = @Schema(fromType = KeyStoreResource.class)
))
public class KeystoreResourceProvider extends SecurityResourceProvider implements SingletonResourceProvider {

    /**
     * Setup logging for the {@link SecurityManager}.
     */
    private final static Logger logger = LoggerFactory.getLogger(KeystoreResourceProvider.class);

    private final char[] keyStorePassword;
    private final KeyStoreManagementService keyStoreManager;

    public KeystoreResourceProvider(String resourceName, KeyStoreService keyStoreService, RepositoryService repoService,
            CryptoService cryptoService, KeyStoreManagementService keyStoreManager) {
        super(resourceName, keyStoreService.getKeyStore(), keyStoreService, repoService, cryptoService);
        this.keyStorePassword = keyStoreService.getKeyStoreDetails().getPassword().toCharArray();
        this.keyStoreManager = keyStoreManager;
    }

    @Actions({
            @Action(name = ACTION_GENERATE_CERT,
                    operationDescription = @Operation(description = "Generate Certificate"),
                    request = @Schema(fromType = GenerateCertRequestAction.class),
                    response = @Schema(fromType = CertificateResource.class)),
            @Action(name = ACTION_GENERATE_CSR,
                    operationDescription = @Operation(description = "Generate CSR"),
                    request = @Schema(fromType = GenerateCsrRequestAction.class),
                    response = @Schema(fromType = GenerateCsrResponseAction.class))
    })
    @Override
    public Promise<ActionResponse, ResourceException> actionInstance(Context context, ActionRequest request) {
        try {
            String alias = request.getContent().get("alias").asString();
            if (ACTION_GENERATE_CERT.equalsIgnoreCase(request.getAction()) ||
                    ACTION_GENERATE_CSR.equalsIgnoreCase(request.getAction())) {
                if (alias == null) {
                    return new BadRequestException("A valid resource ID must be specified in the request").asPromise();
                }
                String algorithm = request.getContent().get("algorithm").defaultTo(DEFAULT_ALGORITHM).asString();
                String signatureAlgorithm = request.getContent().get("signatureAlgorithm")
                        .defaultTo(DEFAULT_SIGNATURE_ALGORITHM).asString();
                int keySize = request.getContent().get("keySize").defaultTo(DEFAULT_KEY_SIZE).asInteger();
                JsonValue result;
                if (ACTION_GENERATE_CERT.equalsIgnoreCase(request.getAction())) {
                    // Generate self-signed certificate
                    if (keyStore.containsAlias(alias)) {
                        return new ConflictException("The resource with ID '" + alias
                                + "' could not be created because there is already another resource with the same ID")
                                .asPromise();
                    } else {
                        logger.info("Generating a new self-signed certificate with the alias {}", alias);
                        String domainName = request.getContent().get("domainName").required().asString();
                        String validFrom = request.getContent().get("validFrom").asString();
                        String validTo = request.getContent().get("validTo").asString();

                        // Generate the cert
                        Pair<X509Certificate, PrivateKey> pair = CertUtil.generateCertificate(domainName, algorithm,
                                keySize, signatureAlgorithm, validFrom, validTo);
                        Certificate cert = pair.getKey();
                        PrivateKey key = pair.getValue();

                        // Add it to the store and reload
                        logger.debug("Adding certificate entry under the alias {}", alias);
                        keyStore.setEntry(alias, new KeyStore.PrivateKeyEntry(key, new Certificate[]{cert}),
                                new KeyStore.PasswordProtection(keyStorePassword));
                        keyStoreService.store();
                        keyStoreManager.reloadSslContext();

                        result = returnCertificate(alias, cert);
                    }
                } else {
                    // Generate CSR
                    Pair<PKCS10CertificationRequest, PrivateKey> csr = generateCSR(alias, algorithm,
                            signatureAlgorithm, keySize, request.getContent());
                    result = returnCertificateRequest(alias, csr.getKey());
                }
                return Responses.newActionResponse(result).asPromise();
            } else {
                return new BadRequestException("Unsupported action " + request.getAction()).asPromise();
            }
        } catch (JsonValueException e) {
            return new BadRequestException(e.getMessage(), e).asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> patchInstance(Context context, PatchRequest request) {
        return new NotSupportedException("Patch operations are not supported").asPromise();
    }

    @Read(operationDescription = @Operation(description = "Read Keystore or Truststore"))
    @Override
    public Promise<ResourceResponse, ResourceException> readInstance(final Context context, final ReadRequest request) {
        try {
            JsonValue content = new JsonValue(new LinkedHashMap<String, Object>(5));
            content.put("type", keyStore.getType());
            content.put("provider", keyStore.getProvider());
            Enumeration<String> aliases = keyStore.aliases();
            List<String> aliasList = new ArrayList<>();
            while (aliases.hasMoreElements()) {
                aliasList.add(aliases.nextElement());
            }
            content.put("aliases", aliasList);
            return newResourceResponse(resourceName, null, content).asPromise();
        } catch (KeyStoreException e) {
            return new InternalServerErrorException(e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> updateInstance(Context context, UpdateRequest request) {
        return new NotSupportedException("Update operations are not supported").asPromise();
    }
}
