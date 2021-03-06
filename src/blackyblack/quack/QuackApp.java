package blackyblack.quack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.http.HttpEntity;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;
import org.json.simple.parser.JSONParser;

import blackyblack.AppConstants;
import blackyblack.Application;
import blackyblack.INxtApi;
import blackyblack.NxtApi;
import blackyblack.http.JSONResponses;
import nrs.Constants;
import nrs.NxtException.NxtApiException;
import nrs.crypto.Crypto;
import nrs.util.Convert;
import nrs.util.Logger;

/*
 * trigger = trigger tx json
 * fullhash = trigger tx full hash
 * triggerBytes = trigger tx unsigned bytes
 * A = initiator
 * B = acceptor
 * 
 * 1. A: quackInit and send invitation to B
 * 2. B: quackCheck() and quackAccept
 * 3. A: quackCheck() and quackValidate
 * 4. A: quackTrigger(triggerBytes, fullhash)
 * 
 * How to restore swapid:
 * a. Check invitation
 * b. Make offline copy
 * c. Scan for init transactions (duplicated in each tx)
 */
public class QuackApp
{
  public static final QuackApp instance = new QuackApp();
  public INxtApi api = new NxtApi();

  public String marketAccount;

  private QuackApp()
  {
  }

  @SuppressWarnings("unchecked")
  public JSONStreamAware init(String secret, String recipient, int finishheight, List<AssetInfo> assets, List<AssetInfo> expectedAssets,
      String privateMessage) throws NxtApiException
  {
    Long height = Application.api.getCurrentBlock();
    int rest = finishheight - height.intValue();

    if (rest <= 0)
    {
      throw new NxtApiException("Too short period until timeout");
    }

    int deadline = rest / 2;
    if (deadline < 3)
      deadline = 3;
    if ((deadline + 1) > rest)
    {
      throw new NxtApiException("Too short period until timeout");
    }
    
    byte[] publicKey = Crypto.getPublicKey(secret);
    Long accountId = Convert.publicKeyToAccountId(publicKey);
    String sender = Convert.rsAccount(accountId);
    
    // now prepare triggertx and send phased transfers
    JSONObject trigger = createtrigger(AppConstants.triggerAccount, secret, 1440, AppConstants.triggerFee);
    String fullhash = Application.api.getFullHash(trigger);
    if (fullhash == null)
    {
      return JSONResponses.MISSING_TRANSACTION_BYTES_OR_JSON;
    }

    String triggerBytes = Application.api.getUnsignedBytes(trigger);
    if (triggerBytes == null)
    {
      return JSONResponses.MISSING_TRANSACTION_BYTES_OR_JSON;
    }

    //insert message with triggerBytes and invitation only in first transaction
    int count = 0;
    for (AssetInfo a : assets)
    {
      if (a.id == null)
        continue;
      
      JSONObject paytx = null;
      JSONObject messageObject = new JSONObject();
      messageObject.put("quack", 1L);
      String encryptedMessage = null;
      
      if(count == 0)
      {
        messageObject = createinfo(messageObject, sender, recipient, triggerBytes, assets, expectedAssets);
        encryptedMessage = privateMessage;
      }
      
      if (a.type.equals("NXT"))
      {
        paytx = api.createPhasedPayment(recipient, secret, fullhash, deadline, finishheight, a.quantity,
            messageObject.toString(), encryptedMessage);
        
        if (paytx != null)
        {
          String txid = (String) paytx.get("transaction");
          Logger.logMessage("Queued transaction: " + txid + "; finish at " + finishheight);
          count++;
        }
        continue;
      }
      
      if (a.type.equals("M"))
      {
        paytx = api.createPhasedMonetary(recipient, secret, fullhash, deadline, finishheight, a.id, a.quantity,
            messageObject.toString(), encryptedMessage);
        
        if (paytx != null)
        {
          String txid = (String) paytx.get("transaction");
          Logger.logMessage("Queued transaction: " + txid + "; finish at " + finishheight);
          count++;
        }
        continue;
      }
      
      paytx = api.createPhasedAsset(recipient, secret, fullhash, deadline, finishheight, a.id, a.quantity,
          messageObject.toString(), encryptedMessage);

      if (paytx != null)
      {
        String txid = (String) paytx.get("transaction");
        Logger.logMessage("Queued transaction: " + txid + "; finish at " + finishheight);
        count++;
      }
      continue;
    }

    JSONObject answer = new JSONObject();
    answer.put("query_status", "good");
    answer.put("triggerBytes", triggerBytes);
    answer.put("triggerhash", fullhash);
    return answer;
  }

  @SuppressWarnings("unchecked")
  public JSONObject createtrigger(String recipient, String secretPhrase, int deadline, long payment) throws NxtApiException
  {    
    JSONObject messageJson = new JSONObject();
    messageJson.put("quack", 1L);
    messageJson.put("trigger", 1L);

    List<BasicNameValuePair> fields = new ArrayList<BasicNameValuePair>();
    fields.add(new BasicNameValuePair("requestType", "sendMoney"));
    fields.add(new BasicNameValuePair("recipient", recipient));
    fields.add(new BasicNameValuePair("secretPhrase", secretPhrase));
    fields.add(new BasicNameValuePair("feeNQT", "" + Constants.ONE_NXT));
    fields.add(new BasicNameValuePair("broadcast", "false"));
    fields.add(new BasicNameValuePair("deadline", "" + deadline));
    fields.add(new BasicNameValuePair("amountNQT", "" + payment));
    fields.add(new BasicNameValuePair("message", messageJson.toString()));
    fields.add(new BasicNameValuePair("messageIsText", "true"));
    fields.add(new BasicNameValuePair("messageIsPrunable", "false"));

    CloseableHttpResponse response = null;
    JSONObject json = null;
    try
    {
      try
      {
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(fields, "UTF-8");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost http = new HttpPost(NxtApi.api());
        http.setHeader("Origin", NxtApi.host);
        http.setEntity(entity);
        response = httpclient.execute(http);
        HttpEntity result = response.getEntity();
        String content = EntityUtils.toString(result);

        JSONParser parser = new JSONParser();
        json = (JSONObject) parser.parse(content);
        if (json == null)
        {
          throw new NxtApiException("no transactionJSON from NRS");
        }
      }
      finally
      {
        if (response != null)
          response.close();
      }
    }
    catch (Exception e)
    {
      throw new NxtApiException(e.getMessage());
    }
    return json;
  }
  
  @SuppressWarnings("unchecked")
  public JSONObject createinfo(JSONObject item, String sender, String recipient, String triggerBytes, List<AssetInfo> assets, List<AssetInfo> expectedAssets)
  {
    item.put("sender", sender);
    item.put("recipient", recipient);
    item.put("triggerBytes", triggerBytes);
    JSONArray assetsArray = new JSONArray();
    for (AssetInfo a : assets)
    {
      assetsArray.add(a.toJson());
    }
    item.put("assets", assetsArray);

    assetsArray = new JSONArray();
    for (AssetInfo a : expectedAssets)
    {
      assetsArray.add(a.toJson());
    }
    item.put("expected_assets", assetsArray);
    return item;
  }

  @SuppressWarnings("unchecked")
  public JSONStreamAware trigger(String secret, String triggerBytes) throws NxtApiException
  {
    List<BasicNameValuePair> fields = new ArrayList<BasicNameValuePair>();
    fields.add(new BasicNameValuePair("requestType", "signTransaction"));
    fields.add(new BasicNameValuePair("unsignedTransactionBytes", triggerBytes));
    fields.add(new BasicNameValuePair("secretPhrase", secret));

    CloseableHttpResponse response = null;
    JSONObject json = null;
    String txBytes = null;
    try
    {
      try
      {
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(fields, "UTF-8");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost http = new HttpPost(NxtApi.api());
        http.setHeader("Origin", NxtApi.host);
        http.setEntity(entity);
        response = httpclient.execute(http);
        HttpEntity result = response.getEntity();
        String content = EntityUtils.toString(result);

        JSONParser parser = new JSONParser();
        json = (JSONObject) parser.parse(content);
        if (json == null)
        {
          throw new NxtApiException("no transactionJSON from NRS");
        }

        txBytes = (String) json.get("transactionBytes");
        if (txBytes == null)
        {
          throw new NxtApiException("no transactionJSON from NRS");
        }
      }
      finally
      {
        if (response != null)
          response.close();
      }
    }
    catch (Exception e)
    {
      throw new NxtApiException(e.getMessage());
    }

    fields = new ArrayList<BasicNameValuePair>();
    fields.add(new BasicNameValuePair("requestType", "broadcastTransaction"));
    fields.add(new BasicNameValuePair("transactionBytes", txBytes));

    response = null;
    String txid = null;
    try
    {
      try
      {
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(fields, "UTF-8");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost http = new HttpPost(NxtApi.api());
        http.setHeader("Origin", NxtApi.host);
        http.setEntity(entity);
        response = httpclient.execute(http);
        HttpEntity result = response.getEntity();
        String content = EntityUtils.toString(result);

        JSONParser parser = new JSONParser();
        json = (JSONObject) parser.parse(content);
        if (json == null)
        {
          throw new NxtApiException("no transactionJSON from NRS");
        }

        txid = (String) json.get("transaction");
        if (txid == null)
        {
          throw new NxtApiException("no transactionJSON from NRS");
        }
      }
      finally
      {
        if (response != null)
          response.close();
      }
    }
    catch (Exception e)
    {
      throw new NxtApiException(e.getMessage());
    }

    JSONObject answer = new JSONObject();
    answer.put("query_status", "good");
    answer.put("txid", txid);
    return answer;
  }

  @SuppressWarnings("unchecked")
  public JSONStreamAware accept(String secret, String recipient, int finishheight, List<AssetInfo> assets, String triggerhash) throws NxtApiException
  {
    // now prepare triggertx and send phased transfers
    Long height = Application.api.getCurrentBlock();
    int rest = finishheight - height.intValue();

    if (rest <= 0)
    {
      throw new NxtApiException("Too short period until timeout");
    }

    int deadline = rest / 2;
    if (deadline < 3)
      deadline = 3;
    if ((deadline + 1) > rest)
    {
      throw new NxtApiException("Too short period until timeout");
    }

    for (AssetInfo a : assets)
    {
      if (a.id == null)
        continue;
      
      JSONObject paytx = null;
      JSONObject messageObject = new JSONObject();
      messageObject.put("quack", 1L);
      
      if (a.type.equals("NXT"))
      {
        paytx = api.createPhasedPayment(recipient, secret, triggerhash, deadline, finishheight, a.quantity, messageObject.toString(), null);
        
        if (paytx != null)
        {
          String txid = (String) paytx.get("transaction");
          Logger.logMessage("Queued transaction: " + txid + "; finish at " + finishheight);
        }
        continue;
      }
      
      if (a.type.equals("M"))
      {
        paytx = api.createPhasedMonetary(recipient, secret, triggerhash, deadline, finishheight, a.id, a.quantity, messageObject.toString(), null);
        
        if (paytx != null)
        {
          String txid = (String) paytx.get("transaction");
          Logger.logMessage("Queued transaction: " + txid + "; finish at " + finishheight);
        }
        continue;
      }

      paytx = api.createPhasedAsset(recipient, secret, triggerhash, deadline, finishheight, a.id, a.quantity, messageObject.toString(), null);
      
      if (paytx != null)
      {
        String txid = (String) paytx.get("transaction");
        Logger.logMessage("Queued transaction: " + txid + "; finish at " + finishheight);
      }
      continue;
    }

    JSONObject answer = new JSONObject();
    answer.put("query_status", "good");
    return answer;
  }
  
  public List<SwapInfo> scanSwaps(String account, int timelimit) throws NxtApiException
  {
    List<SwapInfo> result = new ArrayList<SwapInfo>();
    //map with fullhash as a key
    Map<String, SwapInfo> lookup = new HashMap<String, SwapInfo>();
    //get account transactions down to minHeight
    //look for transactions with quack id
    //combine together transactions with same linked fullhash and trigger = fullhash
    List<JSONObject> txs = api.getTransactions(account, timelimit);
    JSONParser parser = new JSONParser();
    for(JSONObject tx : txs)
    {
      try
      {
        if(tx == null) continue;
        JSONObject attach = (JSONObject) tx.get("attachment");
        if(attach == null) continue;
        String message = (String) attach.get("message");
        if(message == null) continue;
        JSONObject data = (JSONObject) parser.parse(message);
        if(data == null) continue;
        
        if(!isQuack(data)) continue;
        if(isTrigger(data))
        {
          //find fullhash in a map and add trigger here
          String fullhash = Convert.emptyToNull((String) tx.get("fullHash"));
          if(fullhash == null) continue;
          SwapInfo x = lookup.get(fullhash);
          if(x == null)
          {
            x = new SwapInfo();
          }
          
          x.gotTrigger = true;         
          lookup.put(fullhash, x);
          continue;
        }
        
        //phased transactions are added by checking linked fullhash        
        JSONArray linkedhashes = (JSONArray) attach.get("phasingLinkedFullHashes");
        if(linkedhashes == null) continue;
        if(linkedhashes.size() == 0) continue;
        
        Long finishHeight = Convert.nullToZero((Long) attach.get("phasingFinishHeight"));
        if(finishHeight == 0) continue;
        
        String hashdata = Convert.emptyToNull((String) linkedhashes.get(0));
        if(hashdata == null) continue;
        
        String txSender = Convert.emptyToNull((String) tx.get("senderRS"));
        String txRecipient = Convert.emptyToNull((String) tx.get("recipientRS"));
        
        if(txSender == null) continue;
        if(txRecipient == null) continue;
        
        SwapInfo x = lookup.get(hashdata);
        if(x == null)
        {
          x = new SwapInfo();
          x.minFinishHeight = finishHeight.intValue();
        }
        
        if(finishHeight < x.minFinishHeight) x.minFinishHeight = finishHeight.intValue();
        
        x.triggerhash = hashdata;
        //fill x with information about swap if present
        tryUpdateInformation(account, txSender, x, data);
        
        List<BlockAssetInfo> assets = x.assets.get(txSender);
        if(assets == null) assets = new ArrayList<BlockAssetInfo>();
        
        BlockAssetInfo assetInfo = new BlockAssetInfo();
        assetInfo.tx = tx;
        AssetInfo assetInfoData = new AssetInfo();
        
        Long txType = 0L;
        Long txSubtype = 0L;
        
        txType = Convert.nullToZero((Long) tx.get("type"));
        txSubtype= Convert.nullToZero((Long) tx.get("subtype"));
        
        //check if it is payment
        if(txType == 0 && txSubtype == 0)
        {
          assetInfoData.id = "1";
          assetInfoData.type = "NXT";
          String qnt = Convert.emptyToNull((String) tx.get("amountNQT"));
          if(qnt != null)
          {
            assetInfoData.quantity = Long.parseLong(qnt);
          }
          
          assetInfo.asset = assetInfoData;
          assets.add(assetInfo);
          x.assets.put(txSender, assets);
          lookup.put(hashdata, x);
          continue;
        }
        
        //check if it is asset transfer
        if(txType == 2 && txSubtype == 1)
        {
          assetInfoData.id = Convert.emptyToNull((String) attach.get("asset"));
          String qnt = Convert.emptyToNull((String) attach.get("quantityQNT"));
          if(qnt != null)
          {
            assetInfoData.quantity = Long.parseLong(qnt);
          }
          assetInfoData.type = "A";
          
          assetInfo.asset = assetInfoData;
          assets.add(assetInfo);
          x.assets.put(txSender, assets);
          lookup.put(hashdata, x);
          continue;
        }
        
        //check if it is MS transfer
        if(txType == 5 && txSubtype == 3)
        {
          assetInfoData.id = Convert.emptyToNull((String) attach.get("currency"));
          String qnt = Convert.emptyToNull((String) attach.get("units"));
          if(qnt != null)
          {
            assetInfoData.quantity = Long.parseLong(qnt);
          }
          assetInfoData.type = "M";
          
          assetInfo.asset = assetInfoData;
          assets.add(assetInfo);
          x.assets.put(txSender, assets);
          lookup.put(hashdata, x);
          continue;
        }
        
        //unsupported tx
        continue;
      }
      catch(Exception e)
      {
        Logger.logMessage("Failed to parse tx");
      }
    }
    
    for(Entry<String, SwapInfo> k : lookup.entrySet())
    {
      SwapInfo a = k.getValue();
      if(a == null) continue;

      for(String j : a.assets.keySet())
      {
        List<BlockAssetInfo> l = a.assets.get(j);
        if(l == null) continue;
        if(l.size() == 0) continue;
        
        if(j.equals(a.sender))
        {
          a.assetsA = new ArrayList<BlockAssetInfo>(l);
        }
        else if(j.equals(a.recipient))
        {
          a.assetsB = new ArrayList<BlockAssetInfo>(l);
        }
      }
      
      result.add(a);
    }

    return result;
  }
  
  void tryUpdateInformation(String account, String txSender, SwapInfo x, JSONObject data)
  {
    String triggerBytes = getTriggerBytes(data);
    if(triggerBytes == null) return;
    
    //but we already have something - make a security check
    if(x.announcedAssets.size() != 0)
    {
      //not my account is sender - skip
      if(!txSender.equals(account)) return;
    }
      
    JSONObject swapTx = null;
    try
    {
      swapTx = api.parseTransaction(triggerBytes);
    }
    catch (NxtApiException e)
    {
      return;
    }
      
    String feeNqtString = Convert.emptyToNull((String) swapTx.get("amountNQT"));
    Long feeNQT = 0L;
    if(feeNqtString != null)
    {
      feeNQT = Long.parseLong(feeNqtString);
    }
      
    //trigger fee enforcement
    if(feeNQT < AppConstants.triggerFee) return;
      
    String feeRecipient = Convert.emptyToNull((String) swapTx.get("recipientRS"));
    if(feeRecipient == null) return;
    if(!feeRecipient.equals(AppConstants.triggerAccount)) return;
      
    //parse message to get initiator and acceptor
    x.sender = Convert.emptyToNull((String) data.get("sender"));
    x.recipient = Convert.emptyToNull((String) data.get("recipient"));
    x.triggerBytes = triggerBytes;
      
    if(x.sender == null) return;
    if(x.recipient == null) return;
      
    //parse swapid to get assets and expected_assets
    x.announcedAssets = new ArrayList<AssetInfo>();
    JSONArray annAssets = (JSONArray) data.get("assets");
    if(annAssets != null)
    {
      for(Object o : annAssets)
      {
        JSONObject j = (JSONObject) o;
        AssetInfo a = new AssetInfo();
        a.fromJson(j);
        x.announcedAssets.add(a);
      }
    }
      
    x.announcedExpAssets = new ArrayList<AssetInfo>();
    annAssets = (JSONArray) data.get("expected_assets");
    if(annAssets != null)
    {
      for(Object o : annAssets)
      {
        JSONObject j = (JSONObject) o;
        AssetInfo a = new AssetInfo();
        a.fromJson(j);
        x.announcedExpAssets.add(a);
      }
    }    
  }

  boolean isQuack(JSONObject message)
  {
    if(!message.containsKey("quack")) return false;
    Long v = Convert.nullToZero((Long) message.get("quack"));
    if(v != 1) return false;
    return true;
  }
  
  boolean isTrigger(JSONObject message)
  {
    if(!message.containsKey("trigger")) return false;
    Long v = Convert.nullToZero((Long) message.get("trigger"));
    if(v != 1) return false;
    return true;
  }
  
  String getTriggerBytes(JSONObject message)
  {
    if(!message.containsKey("triggerBytes")) return null;
    String v = Convert.emptyToNull((String) message.get("triggerBytes"));
    return v;
  }
}
