package basics.webservices.wehner;

import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Hello world!
 *
 */
public class App 
{

    private static Logger myLog;
    private static Properties myProperties;
    private static HashMap<Integer, String> paymentMethods;

    public static void main( String[] args )
    {
        // Initializing Logger...
        System.setProperty("log4j.configurationFile", "log4j2.xml");
        myLog = LogManager.getLogger(App.class);
        myLog.info("Starting processing...");

        myProperties = new Properties();

        try {
            myProperties.load(Files.newInputStream(Paths.get("webservice.properties")));
        } catch (IOException e) {
            myLog.error("Properties File not found");
            myLog.error(e);
        }

        boolean loop = true;
        try {

            while (loop) {

                CredentialManager cm = new CredentialManager("phc-shop");
                CredentialManager apiKey = new CredentialManager("phc-apikey");

                JSONArray phcOrders = getPHCOrders(1, cm, apiKey);

                myLog.info("We found " + phcOrders.length() + " orders.");

                if (phcOrders.length() > 0){
                    String bearer = getBearerToken();
                    getPaymentMethods(cm, apiKey);

                    for (int i = 0; i < phcOrders.length(); i++){
                        try {
                            placeOrder(phcOrders.getJSONObject(i), bearer, cm , apiKey);
                            TimeUnit.MILLISECONDS.sleep(1000);
                        } catch (Exception e){
                            myLog.error("Processing an order threw an otherwise uncaught error.");
                            myLog.error(e);
                        }
                    }

                    //logoutBearerToken(bearer);
                }

                try {
                    myProperties.load(Files.newInputStream(Paths.get("webservice.properties")));
                } catch (IOException e) {
                    myLog.error("Properties File webservice.properties not found");
                    myLog.error(e);
                }

                loop = Boolean.parseBoolean(myProperties.getProperty("loop"));

            }
        } catch (Exception e){
            myLog.error("Processing threw an otherwise uncaught exception. Processing stopped..");
            myLog.error(e);
        }
        myLog.info("Processing finished... ");
    }

    private static void getPaymentMethods(CredentialManager cm, CredentialManager apiKey){
        WebService ws = new WebService(myProperties.getProperty("phc.paymenttypes.get") );

        ws.setBasicCredentials(cm.getUserName(), cm.getPassword());

        ws.setHeader(apiKey.getUserName(), apiKey.getPassword());

        ws.setMethod("GET");

        Response rsp = sendRequestForResponse(ws);
        if (rsp.isSuccessful()){
            try {
                JSONArray paymentterms = new JSONArray(rsp.body().string());
                try {
                    initializePaymentTerms(paymentterms);
                } catch (Exception e){
                    myLog.error("Initializing Payment Terms threw an error:");
                    myLog.error(e);
                }
            } catch (IOException e) {
                myLog.error("Payment terms could not be fetched. Error reading body:");
                myLog.error(e);
            }
        } else {
            myLog.error("Payment terms could not be fetched.");
            myLog.error("Response: " + rsp);
        }
    }

    private static void initializePaymentTerms(JSONArray pmt){
        paymentMethods = new HashMap<>();
        for( int i = 0; i < pmt.length(); i++){
            JSONObject term = pmt.getJSONObject(i);
            paymentMethods.put(term.getInt("Id"), term.getString("Name"));
        }
        myLog.info("Following payment terms have been loaded: " + paymentMethods);
    }

    private static void logoutBearerToken(String bearer){
        WebService ws = new WebService(myProperties.getProperty("rest.api.auth.logout"));
        ws.setBearerCredentials(bearer);

        Response rsp = sendRequestForResponse(ws);

        if ( rsp.isSuccessful()){
            myLog.info("Logout at Wehner successful");
        }
    }

    private static void placeOrder(JSONObject order, String bearer, CredentialManager cm, CredentialManager apiKey){
        myLog.info("Processing order number " + order.getString("OrderNumber"));

        JSONObject body = buildBody(order);

        WebService ws = new WebService(myProperties.getProperty("rest.api.order.post"));
        ws.setBearerCredentials(bearer);

        ws.setBody(body.toString());

        myLog.info("Calling with body " + body);

        Response rsp = sendRequestForResponse(ws);

        int code = 0;

        try {
            code = rsp.code();
        } catch (Exception e){
            myLog.error("Feching http status from response failed with an exception");
            myLog.error("Response: " + rsp);
            myLog.error(e);
        }

        if (code == 200 || code == 922 || code == 931){
            myLog.info("Return code was " + code);
            updateBillbeeOrder(order.getLong("BillBeeOrderId"), cm, apiKey);
        } else {
            myLog.error("Return code for placing order " + order.getString("OrderNumber") + " was " + code);
            try {
                JSONObject response = new JSONObject(rsp.body().string());
                myLog.error(response.getString("errorMessage"));
            } catch (NullPointerException | IOException e) {
                myLog.error("Could not fetch response body");
                myLog.error(e);
            }
        }

    }

    private static void updateBillbeeOrder(long orderID, CredentialManager cm, CredentialManager apiKey){
        WebService ws = new WebService(myProperties.getProperty("phc.order.put").replace("%BillBeeOrderId%", orderID +""));
        ws.setBasicCredentials(cm.getUserName(), cm.getPassword());

        ws.setHeader(apiKey.getUserName(), apiKey.getPassword());

        JSONObject body = new JSONObject();
        body.put("NewStateId", 16);

        ws.setBody(body.toString());

        ws.setMethod("PUT");

        Response rsp = sendRequestForResponse(ws);

        if (rsp.isSuccessful()){
            myLog.info("Order " + orderID + " updated with status 16 at PHC");
        } else {
            myLog.error("Updating order " + orderID + " at PHC was not successful");
            myLog.error(rsp.toString());
            try {
                myLog.error(rsp.body().string());
            } catch (NullPointerException | IOException e) {
                myLog.error("We have no information about the response body");
                myLog.error(e);
            }
        }
    }

    private static String getInvoiceNumber(JSONObject order){
        String inv ="";
        if (!order.isNull("InvoiceNumber")){
            if (!order.isNull("InvoiceNumberPrefix")){
                inv = order.get("InvoiceNumberPrefix").toString();
            }

            inv += order.get("InvoiceNumber").toString();

            if (!order.isNull("InvoiceNumberPostfix")){
                inv += order.get("InvoiceNumberPostfix").toString();
            }
        }
        return inv;
    }

    private static JSONObject buildBody(JSONObject order){
        JSONArray orderItems = order.getJSONArray("OrderItems");

        JSONObject wehnerOrder = new JSONObject();
        JSONArray wehnerOrderItem = new JSONArray();

        for( int i = 0; i < orderItems.length(); i++){
            JSONObject orderitem = orderItems.getJSONObject(i);
            if ( getNullableString(orderitem.getJSONObject("Product"), "SKU").equalsIgnoreCase("")){
                wehnerOrderItem.put( formatItem(orderitem, true));
            } /*else if (orderitem.getJSONObject("Product").getString("SKU").startsWith("3")){
                if (isCustomCountry(order.getJSONObject("ShippingAddress").getString("CountryISO2"))
                        || order.getInt("PaymentMethod") == 26) {
                    wehnerOrderItem.put( formatItem(orderitem,  false ));
                } else {
                    wehnerOrderItem.put( formatItem(orderitem, true  ));
                }

            }*/ else {
                wehnerOrderItem.put( formatItem(orderitem, false));
            }
        }

        wehnerOrder.put("orderItem", wehnerOrderItem);
        wehnerOrder.put("shipTypeId", getDeliveryType(order.getJSONObject("ShippingAddress").getString("CountryISO2")));
        wehnerOrder.put("syncChannelKey", "phc");
        wehnerOrder.put("externalOrderNo", order.getString("OrderNumber"));
        wehnerOrder.put("priority", 2);
        wehnerOrder.put("customer", getCustomer(order.getJSONObject("InvoiceAddress")));
        wehnerOrder.put("receiveAddress", getReceiveAddress(order.getJSONObject("ShippingAddress")));
        wehnerOrder.put("customerAddress", getReceiveAddress(order.getJSONObject("InvoiceAddress")));

        final int paymentMethod = order.getInt("PaymentMethod");
        try{
            wehnerOrder.put("externalPaymentTypeDescription", paymentMethods.get(paymentMethod));
        } catch (Exception e){
            myLog.error("Error when adding the payment method to the Wehner-Payload. Placing order without payment type.");
            myLog.error(e);
        }

        if (paymentMethod == 26 ||
                paymentMethod == 29 ){
            String inv = getInvoiceNumber(order);
            if (inv.length() > 0){
                wehnerOrder.put("externalInvoiceNo", inv);
            }
        }

        return wehnerOrder;
    }

    private static JSONObject getReceiveAddress(JSONObject shippingAddress){
        JSONObject address = new JSONObject();

        try {
            address.put("company", shippingAddress.getString("Company"));
        } catch ( JSONException je){
            myLog.warn("Company for the customer was not a String. Sending empty string");
            myLog.warn(je);
            address.put("company", "");
        }

        address.put("countryAlpha2Code", shippingAddress.getString("CountryISO2"));
        address.put("firstName", shippingAddress.getString("FirstName"));
        address.put("lastName", getNullableString(shippingAddress,"LastName"));

        try {
            address.put("phoneNumber", shippingAddress.getString("Phone"));
        } catch (JSONException je ){
            myLog.warn("Phone is not a string in Payload. Sending empty phone number");
            myLog.warn(je);
            address.put("phoneNumber", "");
        }

        String street = getNullableString(shippingAddress, "Street") + " " + getNullableString(shippingAddress, "HouseNumber") + " " + getNullableString(shippingAddress, "Line2");
        address.put("street", street.substring(0,Math.min(50,street.length())));

        address.put("zipcode", shippingAddress.getString("Zip"));
        address.put("town", shippingAddress.getString("City").substring(0,Math.min(50,shippingAddress.getString("City").length())));

        return address;
    }

    private static String getNullableString(JSONObject obj, String key){
        String ret = "";
        try{
            ret = obj.getString(key);
        } catch (JSONException je){
            myLog.warn(je);
        }
        return ret;
    }

    private static JSONObject getCustomer(JSONObject invoiceAddress){
        JSONObject customer = new JSONObject();
        customer.put("externalCustNo", invoiceAddress.getLong("BillbeeId") + "");

        return customer;
    }

    private static String getDeliveryType(String iso){
        if (iso.equalsIgnoreCase("DE")){
            return "STEPS:SHIPTYPE_P:13";
        } else {
            return "STEPS:SHIPTYPE_P:71";
        }
    }

    private static boolean isCustomCountry(String iso){
        List<String> customCountries = Arrays.asList(myProperties.getProperty("customs.countries").split(","));

        return customCountries.contains(iso);
    }

    private static JSONObject formatItem(JSONObject orderitem, boolean positionAsText){
        JSONObject body = new JSONObject();

        body.put("externalItemNo", getNullableString(orderitem.getJSONObject("Product"),"SKU"));
        body.put("itemName", orderitem.getJSONObject("Product").getString("Title").substring(0,Math.min(orderitem.getJSONObject("Product").getString("Title").length(),50)));
        body.put("amount", orderitem.getDouble("Quantity"));
        body.put("itemPriceIncl", orderitem.getDouble("TotalPrice") / orderitem.getDouble("Quantity"));
        body.put("vatFactor", (float) 19/100);
        body.put("positionAsText", positionAsText);
        body.put("comment", "");

        return body;
    }

    private static JSONArray getPHCOrders(int page, CredentialManager cm, CredentialManager apiKey){
        WebService ws = new WebService(myProperties.getProperty("phc.order.get") + page);

        ws.setBasicCredentials(cm.getUserName(), cm.getPassword());

        ws.setHeader(apiKey.getUserName(), apiKey.getPassword());

        ws.setMethod("GET");

        JSONObject result = sendRequest(ws);

        JSONArray orders = null;

        if (result.getInt("ErrorCode") == 0){
            orders = result.getJSONArray("Data");

            if (result.getJSONObject("Paging").getInt("TotalPages") > page){
                myLog.info("Multiple pages detected...");
                orders.putAll(getPHCOrders(++page, cm, apiKey));
            }
        } else {
            myLog.error("Error when fetching orders on page " + page + ". Error Code " + result.getInt("ErrorCode"));
            myLog.error(result.getString("ErrorDescription"));
            myLog.error(result.get("ErrorMessage"));
        }
        return orders;
    }

    private static String getBearerToken() {
        String bearer = "";

        WebService ws = new WebService(myProperties.getProperty("rest.api.auth.url"));
        CredentialManager cm = new CredentialManager("phc");

        JSONObject body = new JSONObject();
        body.put("username", cm.getUserName());
        body.put("password", cm.getPassword());

        ws.setBody(body.toString());

        JSONObject result = sendRequest(ws);

        if (result.getBoolean("success")) {
            myLog.info("Success with fetching Bearer token");
            bearer = result.getString("result");
        } else {
            myLog.error("Fetching bearer token failed: " + result);
        }

        return bearer;
    }

    private static Response sendRequestForResponse(WebService ws){
        Response rsp = null;
        try {
            rsp = ws.sendRequest();
        } catch (Exception e ){
            myLog.error("Webservice call to url " + ws.getUrl() + " failed.");
            myLog.error(e);
        }
        return rsp;
    }

    private static JSONObject sendRequest(WebService ws) {
        JSONObject returnBody = new JSONObject();
        Response rsp = null;
        try {
            rsp = sendRequestForResponse(ws);
            returnBody = new JSONObject(rsp.body().string());
        } catch (IOException | NullPointerException e) {
            try{
                myLog.error("The call was made with body: " + ws.getBody());
            } catch (Exception npe){
                myLog.error("We have no information about the body we sent. It's possibly empty.");
            }
            try {
                myLog.error("The return payload was: " + rsp.body().string());
            } catch (IOException | NullPointerException npe) {
                myLog.error("We have no information about the return body");
            }
            myLog.error(e);
        }
        return returnBody;
    }
}
