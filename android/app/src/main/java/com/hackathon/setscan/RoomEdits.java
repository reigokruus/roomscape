package com.hackathon.setscan;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Non-destructive planning overlay. Never writes scan points, photos, planes or wall geometry. */
final class RoomEdits {
    static final int MAX_OBJECTS=200, MAX_PLACED=100;
    final List<Detected> detected=new ArrayList<>();
    final Set<String> hidden=new LinkedHashSet<>();
    private final Deque<Set<String>> undo=new ArrayDeque<>();
    boolean captured;
    String selectedId;
    static final class Detected {
        String id,title; float cx,cz,y0,y1,cosA,sinA,hu,hv; boolean cyl;
        RoomModel.Box box(){RoomModel.Box b=new RoomModel.Box();b.name=title;b.cx=cx;b.cz=cz;b.y0=y0;b.y1=y1;b.cosA=cosA;b.sinA=sinA;b.hu=hu;b.hv=hv;b.cyl=cyl;b.updateAabb();return b;}
        JSONObject json() throws Exception { JSONObject j=new JSONObject();j.put("id",id);j.put("title",title);j.put("cx",cx);j.put("cz",cz);j.put("y0",y0);j.put("y1",y1);j.put("cosA",cosA);j.put("sinA",sinA);j.put("hu",hu);j.put("hv",hv);j.put("cyl",cyl);return j; }
    }
    void capture(List<RoomModel.Box> boxes) {
        if(captured)return;
        ItemCatalog.require(boxes.size() <= MAX_OBJECTS, "This scan has more than 200 detected shapes; no partial snapshot was created");
        Set<String> ids=new HashSet<>();
        for(RoomModel.Box b:boxes){
            String k=b.kind==null?"":b.kind.toLowerCase(Locale.ROOT);
            if(Arrays.asList("wall","floor","ceiling","door","window","noise").contains(k) || Arrays.asList("wall","floor","ceiling","door","window","noise").contains(b.title().toLowerCase(Locale.ROOT)))continue;
            if(!finite(b.cx,b.cz,b.y0,b.y1,b.hu,b.hv,b.cosA,b.sinA)||b.hu<=0||b.hv<=0||b.y1<=b.y0||Math.abs(b.cx)>100||Math.abs(b.cz)>100||Math.abs(b.y0)>100||Math.abs(b.y1)>100||b.hu>20||b.hv>20||Math.abs(b.cosA*b.cosA+b.sinA*b.sinA-1)>.01)continue;
            Detected d=new Detected();d.title=b.title();d.cx=b.cx;d.cz=b.cz;d.y0=b.y0;d.y1=b.y1;d.hu=b.hu;d.hv=b.hv;d.cosA=b.cosA;d.sinA=b.sinA;d.cyl=b.cyl;
            d.id=UUID.nameUUIDFromBytes((Float.toHexString(b.cx)+":"+Float.toHexString(b.cz)+":"+Float.toHexString(b.y0)+":"+Float.toHexString(b.y1)+":"+Float.toHexString(b.hu)+":"+Float.toHexString(b.hv)+":"+Float.toHexString(b.cosA)+":"+Float.toHexString(b.sinA)).getBytes(StandardCharsets.UTF_8)).toString();
            if(ids.add(d.id))detected.add(d);
        }
        // No detections yet is not a permanent empty snapshot; let a later analysis supply them.
        captured=!detected.isEmpty();
    }
    void hide(String id,boolean value){ItemCatalog.require(find(id)!=null,"Detected object no longer exists");if(hidden.contains(id)==value)return;checkpoint();if(value)hidden.add(id);else hidden.remove(id);}
    void restoreAll(){if(hidden.isEmpty())return;checkpoint();hidden.clear();}
    boolean canUndo(){return !undo.isEmpty();}
    boolean undo(){if(undo.isEmpty())return false;hidden.clear();hidden.addAll(undo.removeLast());return true;}
    private void checkpoint(){if(undo.size()==30)undo.removeFirst();undo.addLast(new LinkedHashSet<>(hidden));}
    Detected find(String id){for(Detected d:detected)if(d.id.equals(id))return d;return null;}
    List<Detected> visible(){List<Detected> out=new ArrayList<>();for(Detected d:detected)if(!hidden.contains(d.id))out.add(d);return out;}
    void clear(){detected.clear();hidden.clear();undo.clear();captured=false;selectedId=null;}
    String encode(Lighting lighting) throws Exception {
        JSONObject r=new JSONObject();r.put("schemaVersion",1);r.put("representation","detected-shape-planning-overlay; original scan unchanged");r.put("captured",captured);
        JSONArray a=new JSONArray();for(Detected d:detected)a.put(d.json());r.put("detected",a);r.put("hiddenIds",new JSONArray(hidden));
        JSONArray placed=new JSONArray();synchronized(lighting){for(Lighting.Prop p:lighting.props)if(p.catalog!=null){JSONObject j=new JSONObject();j.put("instanceId",p.instanceId);j.put("item",p.catalog.json());j.put("x",p.x);j.put("z",p.z);j.put("yaw",p.yawDeg);j.put("bottom",p.bottom);if(p.manualSize!=null)j.put("manualSizeMetres",new JSONArray(p.manualSize));placed.put(j);}}
        r.put("placements",placed);String s=r.toString(2);ItemCatalog.require(s.getBytes(StandardCharsets.UTF_8).length<=ItemCatalog.MAX_BYTES,"Planning overlay exceeds 256 KiB");return s;
    }
    static RoomEdits decode(String s,Lighting lighting) throws Exception {
        ItemCatalog.require(s.getBytes(StandardCharsets.UTF_8).length<=ItemCatalog.MAX_BYTES,"Overlay exceeds 256 KiB");JSONObject r=ItemCatalog.object(s);ItemCatalog.require(r.get("schemaVersion") instanceof Number && ((Number)r.get("schemaVersion")).doubleValue()==1,"Unsupported planning schema");
        RoomEdits e=new RoomEdits();e.captured=r.getBoolean("captured");JSONArray a=r.getJSONArray("detected");ItemCatalog.require(a.length()<=MAX_OBJECTS,"Too many detected objects");Set<String> ids=new HashSet<>();
        for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);Detected d=new Detected();d.id=ItemCatalog.text(j,"id",80);ItemCatalog.require(ids.add(d.id),"Duplicate detected ID");d.title=ItemCatalog.text(j,"title",160);d.cx=num(j,"cx",100);d.cz=num(j,"cz",100);d.y0=num(j,"y0",100);d.y1=num(j,"y1",100);d.hu=num(j,"hu",20);d.hv=num(j,"hv",20);d.cosA=num(j,"cosA",1.001f);d.sinA=num(j,"sinA",1.001f);d.cyl=j.getBoolean("cyl");ItemCatalog.require(d.hu>0&&d.hv>0&&d.y1>d.y0&&Math.abs(d.cosA*d.cosA+d.sinA*d.sinA-1)<.01,"Invalid detected shape");e.detected.add(d);}
        ItemCatalog.require(e.captured == !e.detected.isEmpty(),"Inconsistent detected snapshot state");
        JSONArray h=r.getJSONArray("hiddenIds");ItemCatalog.require(h.length()<=MAX_OBJECTS,"Too many hidden IDs");for(int i=0;i<h.length();i++){String id=h.getString(i);ItemCatalog.require(e.find(id)!=null&&e.hidden.add(id),"Unknown/duplicate hidden object ID");}
        JSONArray ps=r.getJSONArray("placements");ItemCatalog.require(ps.length()<=MAX_PLACED,"Too many placements");List<Lighting.Prop> props=new ArrayList<>();Set<String> pids=new HashSet<>();
        for(int i=0;i<ps.length();i++){JSONObject j=ps.getJSONObject(i);ItemCatalog.Item item=new ItemCatalog.Item(j.getJSONObject("item"));Lighting.Prop p=new Lighting.Prop(Lighting.PropKind.CATALOG,num(j,"x",100),num(j,"z",100),num(j,"yaw",36000));p.catalog=item;p.instanceId=ItemCatalog.text(j,"instanceId",80);ItemCatalog.require(pids.add(p.instanceId),"Duplicate placement ID");p.bottom=num(j,"bottom",20);ItemCatalog.require(p.bottom>=0,"Negative base height");if(j.has("manualSizeMetres")){JSONArray m=j.getJSONArray("manualSizeMetres");ItemCatalog.require(m.length()==3,"Manual size needs W,H,D");p.manualSize=new double[3];for(int k=0;k<3;k++){double v=m.getDouble(k);ItemCatalog.require(Double.isFinite(v)&&v>=.001&&v<=20,"Invalid manual size");p.manualSize[k]=v;}}ItemCatalog.require(p.manualSize!=null||item.complete(),"Unknown size cannot be placed");props.add(p);}
        synchronized(lighting){lighting.props.removeIf(p->p.catalog!=null);lighting.props.addAll(props);lighting.selected=-1;}
        return e;
    }
    private static float num(JSONObject j,String k,float max) throws Exception {Object raw=j.get(k);ItemCatalog.require(raw instanceof Number,"Numeric coordinate required");float v=((Number)raw).floatValue();ItemCatalog.require(Float.isFinite(v)&&Math.abs(v)<=max,"Invalid coordinate: "+k);return v;}
    private static boolean finite(float... values){for(float v:values)if(!Float.isFinite(v))return false;return true;}
}
