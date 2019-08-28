// Copyright 2017, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.HashMap;
import java.util.Map;

import io.kubernetes.client.models.V1Secret;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.logging.LoggingFilter;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.work.ContainerResolver;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

import static oracle.kubernetes.operator.logging.LoggingFacade.LOGGER;

/** A Helper Class for retrieving Kubernetes Secrets used by the WebLogic Operator. */
public class SecretHelper {
  public static final String SECRET_DATA_KEY = "secretData";
  // Admin Server Credentials Type Secret
  // has 2 fields (username and password)
  public static final String ADMIN_SERVER_CREDENTIALS_USERNAME = "username";
  public static final String ADMIN_SERVER_CREDENTIALS_PASSWORD = "password";
  private final String namespace;

  /**
   * Constructor.
   *
   * @param namespace Scope for object names and authorization.
   */
  public SecretHelper(String namespace) {

    this.namespace = namespace;
  }

  /**
   * Factory for {@link Step} that asynchronously acquires secret data.
   *
   * @param secretType Secret type
   * @param secretName Secret name
   * @param namespace Namespace
   * @param next Next processing step
   * @return Step for acquiring secret data
   */
  public static Step getSecretData(
      SecretType secretType, String secretName, String namespace, Step next) {
    return new SecretDataStep(secretType, secretName, namespace, next);
  }

  /**
   * Get Data for Specified Secret.
   *
   * @param secretType the secret to retrieve
   * @param secretName the name of the secret.
   * @return a Map containing the secret data fields and values
   */
  public Map<String, byte[]> getSecretData(SecretType secretType, String secretName) {

    LOGGER.entering();
    CallBuilderFactory factory =
        ContainerResolver.getInstance().getContainer().getSpi(CallBuilderFactory.class);

    try {
      if (secretType != SecretType.AdminCredentials) {
        throw new IllegalArgumentException("Invalid secret type");
      } else if (secretName == null) {
        throw new IllegalArgumentException("Invalid secret name");
      }

      LOGGER.fine(MessageKeys.RETRIEVING_SECRET, secretName);

      V1Secret secret = factory.create().readSecret(secretName, namespace);
      if (secret == null || secret.getData() == null) {
        LOGGER.warning(MessageKeys.SECRET_NOT_FOUND, secretName);
        LOGGER.exiting(null);
        return null;
      }

      return harvestAdminSecretData(secret, null);
    } catch (Throwable e) {
      LOGGER.severe(MessageKeys.EXCEPTION, e);
      return null;
    } finally {
      LOGGER.exiting();
    }
  }

  private static Map<String, byte[]> harvestAdminSecretData(
      V1Secret secret, LoggingFilter loggingFilter) {
    Map<String, byte[]> secretData = new HashMap<>();
    byte[] usernameBytes = secret.getData().get(ADMIN_SERVER_CREDENTIALS_USERNAME);
    byte[] passwordBytes = secret.getData().get(ADMIN_SERVER_CREDENTIALS_PASSWORD);

    if (usernameBytes != null) {
      secretData.put(ADMIN_SERVER_CREDENTIALS_USERNAME, usernameBytes);
    } else {
      LOGGER.warning(
          loggingFilter, MessageKeys.SECRET_DATA_NOT_FOUND, ADMIN_SERVER_CREDENTIALS_USERNAME);
    }

    if (passwordBytes != null) {
      secretData.put(ADMIN_SERVER_CREDENTIALS_PASSWORD, passwordBytes);
    } else {
      LOGGER.warning(
          loggingFilter, MessageKeys.SECRET_DATA_NOT_FOUND, ADMIN_SERVER_CREDENTIALS_PASSWORD);
    }
    return secretData;
  }

  public enum SecretType {
    AdminCredentials
  }

  private static class SecretDataStep extends Step {
    private final SecretType secretType;
    private final String secretName;
    private final String namespace;

    SecretDataStep(SecretType secretType, String secretName, String namespace, Step next) {
      super(next);
      this.secretType = secretType;
      this.secretName = secretName;
      this.namespace = namespace;
    }

    @Override
    public NextAction apply(Packet packet) {
      if (secretType != SecretType.AdminCredentials) {
        throw new IllegalArgumentException("Invalid secret type");
      } else if (secretName == null) {
        throw new IllegalArgumentException("Invalid secret name");
      }

      LOGGER.fine(MessageKeys.RETRIEVING_SECRET, secretName);
      Step read =
          new CallBuilder()
              .readSecretAsync(secretName, namespace, new SecretResponseStep(packet, getNext()));

      return doNext(read, packet);
    }

    private class SecretResponseStep extends ResponseStep<V1Secret> {
      private final LoggingFilter loggingFilter;

      SecretResponseStep(Packet packet, Step next) {
        super(next);
        this.loggingFilter = packet.getValue(LoggingFilter.LOGGING_FILTER_PACKET_KEY);
      }

      @Override
      public NextAction onFailure(Packet packet, CallResponse<V1Secret> callResponse) {
        if (callResponse.getStatusCode() == CallBuilder.NOT_FOUND) {
          LOGGER.warning(loggingFilter, MessageKeys.SECRET_NOT_FOUND, secretName);
          return doNext(packet);
        }
        return super.onFailure(packet, callResponse);
      }

      @Override
      public NextAction onSuccess(Packet packet, CallResponse<V1Secret> callResponse) {
        packet.put(
            SECRET_DATA_KEY, harvestAdminSecretData(callResponse.getResult(), loggingFilter));
        return doNext(packet);
      }
    }
  }
}
