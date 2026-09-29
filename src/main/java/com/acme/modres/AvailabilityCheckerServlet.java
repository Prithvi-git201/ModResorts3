package com.acme.modres;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.naming.InitialContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import com.acme.modres.mbean.IOUtils;
import com.acme.modres.mbean.reservation.DateChecker;
import com.acme.modres.mbean.reservation.ReservationCheckerData;
import com.acme.modres.mbean.reservation.Reservation;

@WebServlet({ "/resorts/availability" })
public class AvailabilityCheckerServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private static final Logger logger = Logger.getLogger(AvailabilityCheckerServlet.class.getName());

  private static InitialContext context;

  private ReservationCheckerData reservationCheckerData;

  @Override
  public void init() {
    // load reserved dates
    this.reservationCheckerData = new ReservationCheckerData(IOUtils.getReservationListFromConfig());
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

    String methodName = "doGet";
    logger.entering(AvailabilityCheckerServlet.class.getName(), methodName);
    int statusCode = 200;

    String selectedDateStr = request.getParameter("date");
    boolean parsedDate = reservationCheckerData.setSelectedDate(selectedDateStr);
    if (!parsedDate || reservationCheckerData.getReservationList() == null) {
      statusCode = 500;
      reservationCheckerData.setAvailablility(false);
    } else {
      List<Reservation> reservations = reservationCheckerData.getReservationList().getReservations();
      boolean isAvailible = true;

      // cr-java-0111: Replace server-timezone-sensitive SimpleDateFormat + java.util.Date
      // with timezone-agnostic DateTimeFormatter + LocalDate so that date comparisons
      // produce consistent results across all cloud nodes and regions.
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.DATA_FORMAT);

      for (Reservation reservation : reservations) {
        try {
          LocalDate fromDate     = LocalDate.parse(reservation.getFromDate(), formatter);
          LocalDate toDate       = LocalDate.parse(reservation.getToDate(),   formatter);
          LocalDate selectedDate = reservationCheckerData.getSelectedDate();

          if (selectedDate.isAfter(fromDate) && selectedDate.isBefore(toDate)) {
            isAvailible = false;
            break;
          }
        } catch (DateTimeParseException ex) {
          ex.printStackTrace();
        }
      }

      reservationCheckerData.setAvailablility(isAvailible);

      // Adjust the status code based on availability
      if (!isAvailible) {
        statusCode = 201;
      }
    }

    // Send the response
    PrintWriter out = response.getWriter();
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    out.print("{\"availability\": \"" + String.valueOf(reservationCheckerData.isAvailible()) + "\"}");
    response.setStatus(statusCode);
  }

  /**
   * Returns the weather information for a given city
   */
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    doGet(request, response);
  }

  /**
   * Exports reservations by reading the reservations.json classpath resource,
   * zipping it in-memory, and uploading the resulting ZIP to Azure Blob Storage.
   *
   * <p>All local file system write operations have been removed (rule cr-java-0062).
   * The ZIP archive is built and validated entirely in memory using
   * {@link ZipInputStream} — no {@link java.io.FileOutputStream} or temporary
   * file is ever written to the local disk.
   *
   * <p>All {@code java.io.File} persistent storage operations have been replaced
   * with Azure Blob Storage (rule cr-java-0063). The original code used
   * {@code new File(zipPath)} with {@code ZipValidator} to validate a locally
   * written ZIP file; this has been replaced with an in-memory
   * {@link ZipInputStream} validation followed by an Azure Blob Storage upload,
   * ensuring data durability and cloud-native compliance.
   *
   * <p>Required environment variables (Azure Blob Storage):
   * <ul>
   *   <li>{@code AZURE_STORAGE_CONNECTION_STRING} – Azure Storage connection string</li>
   *   <li>{@code AZURE_STORAGE_CONTAINER_NAME}    – target blob container (default: "reservations")</li>
   * </ul>
   */
  protected int exportRevervations(String selectedDateStr) {
    // ── Step 1: Read reservations.json from classpath into a byte array ──────
    byte[] reservationBytes;
    try (InputStream resourceStream = getClass().getClassLoader()
             .getResourceAsStream("reservations.json");
         ByteArrayOutputStream resourceBuffer = new ByteArrayOutputStream()) {
      if (resourceStream == null) {
        logger.warning("reservations.json not found on classpath");
        return -1;
      }
      byte[] readBuf = new byte[1024];
      int readLen;
      while ((readLen = resourceStream.read(readBuf)) >= 0) {
        resourceBuffer.write(readBuf, 0, readLen);
      }
      reservationBytes = resourceBuffer.toByteArray();
    } catch (IOException e) {
      e.printStackTrace();
      return -1;
    }

    // ── Step 2: Build the ZIP archive in memory ───────────────────────────────
    byte[] zipBytes;
    try (ByteArrayOutputStream zipBuffer = new ByteArrayOutputStream();
         ZipOutputStream zipOut = new ZipOutputStream(zipBuffer)) {
      ZipEntry zipEntry = new ZipEntry("reservations.json");
      zipOut.putNextEntry(zipEntry);
      zipOut.write(reservationBytes);
      zipOut.closeEntry();
      zipOut.finish();
      zipBytes = zipBuffer.toByteArray();
    } catch (IOException e) {
      e.printStackTrace();
      return -1;
    }

    // ── Step 3: Validate the ZIP in memory (no local file write) ─────────────
    // The local FileOutputStream write has been replaced with an in-memory
    // ZipInputStream validation to comply with cr-java-0062 (no local file
    // system write operations in cloud/containerised environments).
    try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry = zipIn.getNextEntry();
      if (entry == null) {
        logger.warning("Generated ZIP archive is empty – validation failed");
        return -1;
      }
      // Drain the entry to confirm it is readable
      byte[] drainBuf = new byte[1024];
      while (zipIn.read(drainBuf) >= 0) { /* drain */ }
      zipIn.closeEntry();
      logger.fine("In-memory ZIP validation passed for entry: " + entry.getName());
    } catch (IOException e) {
      logger.warning("In-memory ZIP validation failed: " + e.getMessage());
      e.printStackTrace();
      return -1;
    }

    // ── Step 4: Upload the ZIP to Azure Blob Storage ──────────────────────────
    try {
      String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
      if (connectionString == null || connectionString.isEmpty()) {
        logger.severe("AZURE_STORAGE_CONNECTION_STRING environment variable is not set");
        return -1;
      }
      String containerName = System.getenv("AZURE_STORAGE_CONTAINER_NAME");
      if (containerName == null || containerName.isEmpty()) {
        containerName = "reservations";
      }

      BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
          .connectionString(connectionString)
          .buildClient();

      BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
      if (!containerClient.exists()) {
        containerClient.create();
      }

      BlobClient blobClient = containerClient.getBlobClient("reservations.zip");
      try (ByteArrayInputStream uploadStream = new ByteArrayInputStream(zipBytes)) {
        blobClient.upload(uploadStream, zipBytes.length, true);
      }

      logger.info("reservations.zip successfully uploaded to Azure Blob Storage container: " + containerName);
      return 0;
    } catch (Throwable e) {
      e.printStackTrace();
      return -1;
    }
  }

}
