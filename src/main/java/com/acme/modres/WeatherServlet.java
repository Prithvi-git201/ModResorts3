package com.acme.modres;

import com.acme.modres.db.ModResortsCustomerInformation;
import com.acme.modres.exception.ExceptionHandler;
import com.acme.modres.mbean.AppInfo;

import java.io.BufferedReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import javax.inject.Inject;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.annotation.WebServlet;

// Azure Key Vault + Managed Identity imports (cr-java-0113)
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.core.exception.ResourceNotFoundException;

@WebServlet({ "/resorts/weather" })
public class WeatherServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Inject
  private ModResortsCustomerInformation customerInfo;

  // Azure Key Vault secret name for the Weather API key (cr-java-0113).
  // The actual secret value is stored in Azure Key Vault and retrieved at
  // runtime via Managed Identity – it is never embedded in source code.
  private static final String WEATHER_API_KEY_SECRET_NAME = "WEATHER-API-KEY";

  // Environment variable that holds the Azure Key Vault URI, e.g.
  // https://<vault-name>.vault.azure.net/
  private static final String KEY_VAULT_URI_ENV = "AZURE_KEY_VAULT_URI";

  private static final Logger logger = Logger.getLogger(WeatherServlet.class.getName());

  private static InitialContext context;

  // Azure Key Vault SecretClient – uses DefaultAzureCredential (Managed Identity
  // in Azure, env-based credentials locally) so no credentials are hard-coded.
  private SecretClient secretClient;

  MBeanServer server;
  ObjectName weatherON;
  ObjectInstance mbean;

  @Override
  public void init() {
    server = ManagementFactory.getPlatformMBeanServer();
    try {
      weatherON = new ObjectName("com.acme.modres.mbean:name=appInfo");
    } catch (MalformedObjectNameException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    try {
      if (weatherON != null) {
        mbean = server.registerMBean(new AppInfo(), weatherON);
      }
    } catch (InstanceAlreadyExistsException | MBeanRegistrationException | NotCompliantMBeanException e) {
      e.printStackTrace();
    }
    context = setInitialContextProps();

    // Initialise the Azure Key Vault SecretClient using Managed Identity (cr-java-0113).
    // The vault URI is supplied via the AZURE_KEY_VAULT_URI environment variable so
    // no credentials or vault addresses are hard-coded in source.
    String keyVaultUri = System.getenv(KEY_VAULT_URI_ENV);
    if (keyVaultUri != null && !keyVaultUri.trim().isEmpty()) {
      secretClient = new SecretClientBuilder()
          .vaultUrl(keyVaultUri)
          .credential(new DefaultAzureCredentialBuilder().build())
          .buildClient();
      logger.info("Azure Key Vault SecretClient initialised for vault: " + keyVaultUri);
    } else {
      logger.warning("Environment variable " + KEY_VAULT_URI_ENV + " is not set. "
          + "Weather API key will not be retrieved from Azure Key Vault.");
    }
  }

  @Override
  public void destroy() {
    if (mbean != null) {
      try {
        server.unregisterMBean(weatherON);
      } catch (MBeanRegistrationException | InstanceNotFoundException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }

  @Override
  protected void doGet(HttpServletRequest request,
      HttpServletResponse response) throws IOException, ServletException {

    String methodName = "doGet";
    logger.entering(WeatherServlet.class.getName(), methodName);

    try {
      MBeanInfo weatherConfig = server.getMBeanInfo(weatherON);
    } catch (IntrospectionException | InstanceNotFoundException | ReflectionException e) {
      e.printStackTrace();
    }

    String city = request.getParameter("selectedCity");
    logger.log(Level.FINE, "requested city is " + city);

    // Retrieve the Weather API key from Azure Key Vault via Managed Identity
    // (cr-java-0113). The secret is never stored in source code or property files.
    String weatherAPIKey = getWeatherApiKeyFromKeyVault();
    String mockedKey = mockKey(weatherAPIKey);
    logger.log(Level.FINE, "weatherAPIKey is " + mockedKey);

    if (weatherAPIKey != null && weatherAPIKey.trim().length() > 0) {
      logger.info("weatherAPIKey is found, system will provide the real time weather data for the city " + city);
      getRealTimeWeatherData(city, weatherAPIKey, response);
    } else {
      logger.info(
          "weatherAPIKey is not found, will provide the weather data dated August 10th, 2018 for the city " + city);
      getDefaultWeatherData(city, response);
    }
  }

  /**
   * Retrieves the Weather API key from Azure Key Vault using Managed Identity
   * (cr-java-0113). Falls back to {@code null} if the Key Vault client is not
   * configured or the secret cannot be found, allowing the servlet to serve
   * default weather data gracefully.
   *
   * @return the API key string, or {@code null} if unavailable
   */
  private String getWeatherApiKeyFromKeyVault() {
    if (secretClient == null) {
      logger.warning("Azure Key Vault SecretClient is not initialised. "
          + "Ensure " + KEY_VAULT_URI_ENV + " is set and Managed Identity is configured.");
      return null;
    }
    try {
      KeyVaultSecret secret = secretClient.getSecret(WEATHER_API_KEY_SECRET_NAME);
      return secret.getValue();
    } catch (ResourceNotFoundException e) {
      logger.warning("Secret '" + WEATHER_API_KEY_SECRET_NAME + "' not found in Azure Key Vault: " + e.getMessage());
      return null;
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to retrieve secret '" + WEATHER_API_KEY_SECRET_NAME
          + "' from Azure Key Vault: " + e.getMessage(), e);
      return null;
    }
  }

  private void getRealTimeWeatherData(String city, String apiKey, HttpServletResponse response)
      throws ServletException, IOException {
    String resturl = null;
    String resturlbase = Constants.WUNDERGROUND_API_PREFIX + apiKey + Constants.WUNDERGROUND_API_PART;

    if (Constants.PARIS.equals(city)) {
      resturl = resturlbase + "France/Paris.json";
    } else if (Constants.LAS_VEGAS.equals(city)) {
      resturl = resturlbase + "NV/Las_Vegas.json";
    } else if (Constants.SAN_FRANCISCO.equals(city)) {
      resturl = resturlbase + "/CA/San_Francisco.json";
    } else if (Constants.MIAMI.equals(city)) {
      resturl = resturlbase + "FL/Miami.json";
    } else if (Constants.CORK.equals(city)) {
      resturl = resturlbase + "ireland/cork.json";
    } else if (Constants.BARCELONA.equals(city)) {
      resturl = resturlbase + "Spain/Barcelona.json";
    } else {
      String errorMsg = "Sorry, the weather information for your selected city: " + city +
          " is not available.  Valid selections are: " + Constants.SUPPORTED_CITIES;
      ExceptionHandler.handleException(null, errorMsg, logger);
    }

    URL obj = null;
    HttpURLConnection con = null;
    try {
      obj = new URL(resturl);
      con = (HttpURLConnection) obj.openConnection();
      con.setRequestMethod("GET");
    } catch (MalformedURLException e1) {
      String errorMsg = "Caught MalformedURLException. Please make sure the url is correct.";
      ExceptionHandler.handleException(e1, errorMsg, logger);
    } catch (ProtocolException e2) {
      String errorMsg = "Caught ProtocolException: " + e2.getMessage()
          + ". Not able to set request method to http connection.";
      ExceptionHandler.handleException(e2, errorMsg, logger);
    } catch (IOException e3) {
      String errorMsg = "Caught IOException: " + e3.getMessage() + ". Not able to open connection.";
      ExceptionHandler.handleException(e3, errorMsg, logger);
    }

    int responseCode = con.getResponseCode();
    logger.log(Level.FINEST, "Response Code: " + responseCode);

    if (responseCode >= 200 && responseCode < 300) {

      BufferedReader in = null;
      ServletOutputStream out = null;

      try {
        in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine = null;
        StringBuffer responseStr = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
          responseStr.append(inputLine);
        }

        response.setContentType("application/json");
        out = response.getOutputStream();
        out.print(responseStr.toString());
        logger.log(Level.FINE, "responseStr: " + responseStr);
      } catch (Exception e) {
        String errorMsg = "Problem occured when processing the weather server response.";
        ExceptionHandler.handleException(e, errorMsg, logger);
      } finally {
        if (in != null) {
          in.close();
        }
        if (out != null) {
          out.close();
        }
        in = null;
        out = null;
      }
    } else {
      String errorMsg = "REST API call " + resturl + " returns an error response: " + responseCode;
      ExceptionHandler.handleException(null, errorMsg, logger);
    }
  }

  private void getDefaultWeatherData(String city, HttpServletResponse response)
      throws ServletException, IOException {
    DefaultWeatherData defaultWeatherData = null;

    try {
      defaultWeatherData = new DefaultWeatherData(city);
    } catch (UnsupportedOperationException e) {
      ExceptionHandler.handleException(e, e.getMessage(), logger);
    }

    ServletOutputStream out = null;

    try {
      String responseStr = defaultWeatherData.getDefaultWeatherData();
      response.setContentType("application/json");
      out = response.getOutputStream();
      out.print(responseStr.toString());
      logger.log(Level.FINEST, "responseStr: " + responseStr);
    } catch (Exception e) {
      String errorMsg = "Problem occured when getting the default weather data.";
      ExceptionHandler.handleException(e, errorMsg, logger);
    } finally {

      if (out != null) {
        out.close();
      }

      out = null;
    }
  }

  /**
   * Returns the weather information for a given city
   */
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    doGet(request, response);
  }

  private static String mockKey(String toBeMocked) {
    if (toBeMocked == null) {
      return null;
    }
    String lastToKeep = toBeMocked.substring(toBeMocked.length() - 3);
    return "*********" + lastToKeep;
  }

  private String configureEnvDiscovery() {

    String serverEnv = "";

    serverEnv += com.ibm.websphere.runtime.ServerName.getDisplayName();
    serverEnv += com.ibm.websphere.runtime.ServerName.getFullName();

    return serverEnv;
  }

  private InitialContext setInitialContextProps() {

    Hashtable ht = new Hashtable();

    ht.put("java.naming.factory.initial", "com.ibm.websphere.naming.WsnInitialContextFactory");
    ht.put("java.naming.provider.url", "corbaloc:iiop:localhost:2809");

    InitialContext ctx = null;
    try {
      ctx = new InitialContext(ht);
    } catch (NamingException e) {
      e.printStackTrace();
    }

    return ctx;
  }
}
