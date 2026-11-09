package com.hackathon.setscan;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Bounded, explicit factual feed. This is NOT arbitrary product-page extraction or a pricing API. */
final class ItemCatalog {
    static final int MAX_BYTES = 256 * 1024, MAX_ITEMS = 200;
    static final String DEFAULT_FEED = "https://raw.githubusercontent.com/reigokruus/roomscape/testable-app/android/app/src/main/assets/equipment-catalog.json";
    static final class Item {
        final String id, manufacturer, model, category, dimensionKind, sourceUrl, retrievedDate, sourceQuote, axisNotes;
        final Double width, height, depth; // metres, null means unknown (not a default estimate)
        Item(JSONObject j) throws Exception {
            id = text(j,"id",80); require(id.matches("[a-z0-9][a-z0-9._-]*"),"Invalid item ID");
            manufacturer=text(j,"manufacturer",100); model=text(j,"model",160); category=text(j,"category",30);
            require(Arrays.asList("camera","light","stand","furniture").contains(category),"Unknown category");
            dimensionKind=text(j,"dimensionKind",30);
            require(Arrays.asList("body","deployed","assembled").contains(dimensionKind),"Package/ambiguous dimension kinds cannot be placed");
            sourceUrl=text(j,"sourceUrl",2000); publicHttps(sourceUrl);
            retrievedDate=text(j,"retrievedDate",10); validDate(retrievedDate);
            sourceQuote=text(j,"sourceQuote",1200); axisNotes=text(j,"axisNotes",1200);
            String unit=text(j,"unit",4); double scale=unitScale(unit);
            width=dimension(j,"width",scale); height=dimension(j,"height",scale); depth=dimension(j,"depth",scale);
        }
        boolean complete() { return width!=null && height!=null && depth!=null; }
        String title() { return manufacturer+" "+model; }
        boolean matches(String query) { return (title()+" "+category+" "+id).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
        String dimensions() { return "W "+display(width)+" × H "+display(height)+" × D "+display(depth)+" · "+dimensionKind; }
        private static String display(Double d) { return d==null ? "unknown" : String.format(Locale.US,"%.1f mm",d*1000); }
        JSONObject json() throws Exception {
            JSONObject j=new JSONObject(); j.put("id",id); j.put("manufacturer",manufacturer); j.put("model",model); j.put("category",category); j.put("dimensionKind",dimensionKind);
            j.put("sourceUrl",sourceUrl); j.put("retrievedDate",retrievedDate); j.put("sourceQuote",sourceQuote); j.put("axisNotes",axisNotes); j.put("unit","m");
            j.put("width",width==null?JSONObject.NULL:width); j.put("height",height==null?JSONObject.NULL:height); j.put("depth",depth==null?JSONObject.NULL:depth); return j;
        }
    }
    static List<Item> parse(String text) throws Exception {
        require(text.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES,"Catalog exceeds 256 KiB");
        JSONObject root=object(text); require(root.get("schemaVersion") instanceof Number && ((Number)root.get("schemaVersion")).doubleValue()==1,"Unsupported catalog schema");
        JSONArray a=root.getJSONArray("items"); require(a.length()>0 && a.length()<=MAX_ITEMS,"Catalog needs 1–200 items");
        List<Item> out=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<a.length();i++){ Item item=new Item(a.getJSONObject(i)); require(ids.add(item.id),"Duplicate item ID: "+item.id); out.add(item); } return out;
    }
    static List<Item> merge(List<Item> old,List<Item> incoming) throws Exception {
        LinkedHashMap<String,Item> m=new LinkedHashMap<>(); for(Item i:old)m.put(i.id,i);
        for(Item i:incoming){ Item prior=m.get(i.id); if(prior!=null){ require(prior.manufacturer.equals(i.manufacturer)&&prior.model.equals(i.model),"ID belongs to a different model: "+i.id); require(i.retrievedDate.compareTo(prior.retrievedDate)>=0,"Older source facts rejected: "+i.id); } m.put(i.id,i); }
        require(m.size()<=MAX_ITEMS,"Merged catalog exceeds 200 items"); List<Item> merged=new ArrayList<>(m.values()); require(encode(merged).getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES,"Merged catalog exceeds 256 KiB"); return merged;
    }
    static String encode(List<Item> items) throws Exception { JSONObject r=new JSONObject();r.put("schemaVersion",1);JSONArray a=new JSONArray();for(Item i:items)a.put(i.json());r.put("items",a);return r.toString(2); }
    static double unitScale(String unit) { switch(unit){case "mm":return .001;case "cm":return .01;case "m":return 1;case "in":return .0254;default:throw new IllegalArgumentException("Use mm, cm, m or in");} }
    static Double dimension(JSONObject j,String key,double scale) throws Exception {
        if(j.isNull(key))return null; Object raw=j.get(key); require(raw instanceof Number,"Dimensions must be numbers or null"); double v=((Number)raw).doubleValue()*scale;
        require(Double.isFinite(v)&&v>=.001&&v<=20,"Dimension must be 1 mm–20 m"); return v;
    }
    static String text(JSONObject j,String key,int max) throws Exception { Object v=j.get(key);require(v instanceof String,"Text required: "+key);String s=((String)v).trim();require(!s.isEmpty()&&s.length()<=max&&!s.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*"),"Invalid text: "+key);return s; }
    static void validDate(String s) throws Exception { require(s.matches("\\d{4}-\\d{2}-\\d{2}"),"Date must be YYYY-MM-DD");SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));Date d=f.parse(s);require(!d.after(new Date()),"Retrieval date is in the future"); }
    static URI publicHttps(String s) throws Exception { URI u=new URI(s);String h=u.getHost();require("https".equals(u.getScheme())&&h!=null&&u.getUserInfo()==null&&u.getPort()==-1&&!h.equals("localhost")&&!h.contains(":")&&!h.matches("[0-9.]+")&&h.contains("."),"Use a public HTTPS URL without credentials or port");return u; }
    static URI feedUri(String s) throws Exception { URI u=publicHttps(s);require("raw.githubusercontent.com".equals(u.getHost())&&(u.getPath().startsWith("/reigokruus/roomscape/")||u.getPath().startsWith("/reigokruus/shootlocal/"))&&u.getPath().endsWith(".json")&&!u.getPath().contains("..")&&u.getRawQuery()==null&&u.getFragment()==null,"Only JSON feeds in reigokruus/roomscape on raw.githubusercontent.com are supported"); return u; }
    static String read(InputStream in) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;){require(b.size()+n<=MAX_BYTES,"File exceeds 256 KiB");b.write(buf,0,n);}return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b.toByteArray())).toString(); }
    static JSONObject object(String s) throws Exception {
        require(s.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES,"JSON exceeds 256 KiB");
        int depth=0; boolean quote=false,escape=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(quote){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quote=false;}
            else if(c=='"')quote=true;else if(c=='{'||c=='['){require(++depth<=12,"JSON nesting exceeds 12 levels");}else if(c=='}'||c==']'){require(--depth>=0,"Unbalanced JSON");}}
        require(depth==0&&!quote,"Unbalanced JSON");JSONTokener t=new JSONTokener(s);Object value=t.nextValue();require(value instanceof JSONObject&&t.nextClean()==0,"Expected a single JSON object");return (JSONObject)value;
    }
    static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
}
