package com.acme.modres.mbean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.logging.Logger;

import com.acme.modres.mbean.reservation.ReservationList;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.gson.Gson;

/**
 * Utility class for reading configuration resources from the classpath and
 * persisting temporary / intermediate data to Azure Blob Storage.
 *
 * <h3>Cloud-readiness remediation (cr-java-0112 – Local Temporary Storage Reliance)</h3>
 * <p>The original implementation of {@code getFileFromRelativePath} wrote
 * classpath resources to a temporary file via {@code File.createTempFile} and
 * {@code FileOutputStream} so that {@code JsonInputStream} (which extends
 * {@code FileInputStream}) could read them back.  In cloud / containerised
 * environments the local file system is ephemeral: data written to {@code /tmp}
 * or any other local directory is lost on container restart, scale-out, or
 * redeployment.
 *
 * <p>This class replaces that pattern in two ways:
 * <ol>
 *   <li><b>Static configuration files</b> ({@code ops.json},
 *       {@code reservations.json}) – read directly from the classpath
 *       {@link InputStream} using Gson; no local write is ever performed.</li>
 *   <li><b>Temporary / intermediate data that must survive container lifecycle
 *       events</b> – stored in <em>Azure Blob Storage</em> via the Azure SDK
 *       for Java ({@link #uploadTempDataToBlob(String, byte[])}).  The
 *       corresponding {@link #downloadTempDataFromBlob(String)} method
 *       retrieves the data from Blob Storage instead of reading a local temp
 *       file.</li>
 * </ol>
 *
 * <h3>Required environment variables (Azure Blob Storage)</h3>
 * <ul>
 *   <li>{@code AZURE_STORAGE_CONNECTION_STRING} – Azure Storage connection string</li>
 *   <li>{@code AZURE_STORAGE_TEMP_CONTAINER}    – blob container for temp data
 *       (default: {@code "app-temp-data"})</li>
 * </ul>
 */
public final class IOUtils {

  private static final Logger logger = Logger.getLogger(IOUtils.class.getName());

  /** Default container name used when {@code AZURE_STORAGE_TEMP_CONTAINER} is not set. */
  private static final String DEFAULT_TEMP_CONTAINER = "app-temp-data";

  // ── Private constructor – utility class ──────────────────────────────────
  private IOUtils() {}

  // =========================================================================
  // Classpath JSON helpers (replaces File.createTempFile pattern)
  // =========================================================================

  /**
   * Parses a classpath JSON resource directly into the requested type.
   *
   * <p>This method replaces the old {@code getFileFromRelativePath} helper that
   * wrote the resource to a local {@code File.createTempFile} / {@code FileOutputStream}
   * before reading it back.  The new implementation streams the resource directly
   * from the classpath, eliminating all local temporary file system write operations
   * (cr-java-0112 remediation).
   *
   * @param path classpath-relative resource path (e.g. {@code "ops.json"})
   * @param cls  target type for Gson deserialisation
   * @return deserialised object, or {@code null} if the resource is missing or
   *         cannot be parsed
   */
  private static <T> T parseClasspathJson(String path, Class<T> cls) {
    try (InputStream is = IOUtils.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        logger.warning("IOUtils: classpath resource not found: " + path);
        return null;
      }
      Gson gson = new Gson();
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
      return gson.fromJson(reader, cls);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Returns the operations metadata list parsed from the {@code ops.json}
   * classpath resource.
   *
   * @return {@link OpMetadataList} (never {@code null}; returns an empty list
   *         if the resource is missing)
   */
  public static OpMetadataList getOpListFromConfig() {
    OpMetadataList opList = parseClasspathJson("ops.json", OpMetadataList.class);
    if (opList == null) {
      opList = new OpMetadataList(); // empty default
    }
    return opList;
  }

  /**
   * Returns the reservation list parsed from the {@code reservations.json}
   * classpath resource.
   *
   * @return {@link ReservationList} (never {@code null}; returns an empty list
   *         if the resource is missing)
   */
  public static ReservationList getReservationListFromConfig() {
    ReservationList reservationList = parseClasspathJson("reservations.json", ReservationList.class);
    if (reservationList == null) {
      reservationList = new ReservationList(); // empty default
    }
    return reservationList;
  }

  // =========================================================================
  // Azure Blob Storage helpers (replaces /tmp / local temp-file persistence)
  // =========================================================================

  /**
   * Uploads temporary or intermediate processing data to Azure Blob Storage.
   *
   * <p>This method is the cloud-native replacement for writing data to a local
   * temporary directory (e.g. {@code /tmp}).  Because container file systems are
   * ephemeral, any data that must survive a container restart, scale-out event,
   * or redeployment MUST be stored in durable external storage.  Azure Blob
   * Storage provides that durability (cr-java-0112 remediation).
   *
   * @param blobName the logical name / key for the temporary data blob
   * @param data     the raw bytes to persist
   * @return {@code true} if the upload succeeded; {@code false} otherwise
   */
  public static boolean uploadTempDataToBlob(String blobName, byte[] data) {
    try {
      String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
      if (connectionString == null || connectionString.isEmpty()) {
        logger.severe("AZURE_STORAGE_CONNECTION_STRING environment variable is not set");
        return false;
      }
      String containerName = System.getenv("AZURE_STORAGE_TEMP_CONTAINER");
      if (containerName == null || containerName.isEmpty()) {
        containerName = DEFAULT_TEMP_CONTAINER;
      }

      BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
          .connectionString(connectionString)
          .buildClient();

      BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
      if (!containerClient.exists()) {
        containerClient.create();
        logger.info("Created Azure Blob Storage container: " + containerName);
      }

      BlobClient blobClient = containerClient.getBlobClient(blobName);
      try (ByteArrayInputStream uploadStream = new ByteArrayInputStream(data)) {
        blobClient.upload(uploadStream, data.length, true /* overwrite */);
      }

      logger.info("Temporary data uploaded to Azure Blob Storage: " + containerName + "/" + blobName);
      return true;
    } catch (Exception e) {
      logger.severe("Failed to upload temporary data to Azure Blob Storage: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }

  /**
   * Downloads temporary or intermediate processing data from Azure Blob Storage.
   *
   * <p>This method is the cloud-native replacement for reading data from a local
   * temporary file.  It retrieves data previously stored via
   * {@link #uploadTempDataToBlob(String, byte[])} from Azure Blob Storage,
   * ensuring data availability across container lifecycle events
   * (cr-java-0112 remediation).
   *
   * @param blobName the logical name / key of the temporary data blob
   * @return the raw bytes of the blob, or {@code null} if the blob does not
   *         exist or the download fails
   */
  public static byte[] downloadTempDataFromBlob(String blobName) {
    try {
      String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
      if (connectionString == null || connectionString.isEmpty()) {
        logger.severe("AZURE_STORAGE_CONNECTION_STRING environment variable is not set");
        return null;
      }
      String containerName = System.getenv("AZURE_STORAGE_TEMP_CONTAINER");
      if (containerName == null || containerName.isEmpty()) {
        containerName = DEFAULT_TEMP_CONTAINER;
      }

      BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
          .connectionString(connectionString)
          .buildClient();

      BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
      if (!containerClient.exists()) {
        logger.warning("Azure Blob Storage container does not exist: " + containerName);
        return null;
      }

      BlobClient blobClient = containerClient.getBlobClient(blobName);
      if (!blobClient.exists()) {
        logger.warning("Blob not found in Azure Blob Storage: " + containerName + "/" + blobName);
        return null;
      }

      try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
        blobClient.downloadStream(outputStream);
        logger.info("Temporary data downloaded from Azure Blob Storage: " + containerName + "/" + blobName);
        return outputStream.toByteArray();
      }
    } catch (Exception e) {
      logger.severe("Failed to download temporary data from Azure Blob Storage: " + e.getMessage());
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Deletes a temporary data blob from Azure Blob Storage once it is no longer
   * needed.
   *
   * <p>Callers should invoke this method after successfully processing the
   * temporary data to avoid unnecessary storage costs.
   *
   * @param blobName the logical name / key of the temporary data blob to delete
   * @return {@code true} if the blob was deleted (or did not exist);
   *         {@code false} if the deletion failed
   */
  public static boolean deleteTempDataFromBlob(String blobName) {
    try {
      String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
      if (connectionString == null || connectionString.isEmpty()) {
        logger.severe("AZURE_STORAGE_CONNECTION_STRING environment variable is not set");
        return false;
      }
      String containerName = System.getenv("AZURE_STORAGE_TEMP_CONTAINER");
      if (containerName == null || containerName.isEmpty()) {
        containerName = DEFAULT_TEMP_CONTAINER;
      }

      BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
          .connectionString(connectionString)
          .buildClient();

      BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
      if (!containerClient.exists()) {
        return true; // nothing to delete
      }

      BlobClient blobClient = containerClient.getBlobClient(blobName);
      if (blobClient.exists()) {
        blobClient.delete();
        logger.info("Temporary blob deleted from Azure Blob Storage: " + containerName + "/" + blobName);
      }
      return true;
    } catch (Exception e) {
      logger.severe("Failed to delete temporary data from Azure Blob Storage: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }
}
