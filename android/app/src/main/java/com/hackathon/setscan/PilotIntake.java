package com.hackathon.setscan;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Opt-in demo notebook. Interest is never a confirmed purchase, payment or verified budget authority. */
final class PilotIntake {
    static final int MAX_RECORDS = 100, MAX_BYTES = 256 * 1024;
    static final String CONSENT_VERSION = "cinex-paid-pilot-followup-v1";
    static final String INTEREST_ONLY = "UNVERIFIED_INTEREST_NOT_A_COMMITMENT_OR_PAYMENT";
    enum Authority { NOT_STATED, USER_OR_EVALUATOR, RECOMMENDS_ONLY, CAN_APPROVE_PILOT_BUDGET, NOT_SURE }
    static final String[] AUTHORITY_LABELS = {"Not stated", "User / evaluator", "Recommends only", "Can approve a pilot budget (self-reported)", "Not sure"};
    static final class Validation extends IllegalArgumentException { Validation(String m) { super(m); } }
    static void require(boolean condition, String message) { if (!condition) throw new Validation(message); }

    static final class Host {
        final String name, contact;
        Host(String name, String contact) { this.name = text(name, "Demo host name", 120, true); this.contact = email(contact); }
        boolean same(Host h) { return h != null && name.equals(h.name) && contact.equalsIgnoreCase(h.contact); }
        JSONObject json() throws Exception { return new JSONObject().put("name", name).put("contact", contact); }
    }
    static String notice(Host h) {
        require(h != null, "Identify the demo host before collecting a contact.");
        return "I agree that " + h.name + " (contact: " + h.contact + ") may use the contact details and project information I provide to follow up with me about a PAID CineX pilot. "
                + "This is interest only, not a purchase, subscription or payment commitment. My details are stored on this device; the CineX team does NOT automatically receive them. "
                + "The demo host may explicitly export this record for that follow-up. I can ask that host to revoke permission or delete this local record. Exported copies must be handled separately.";
    }
    static final class Draft {
        final String name, email, role, company, project, problem;
        final Authority authority;
        Draft(String name, String email, String role, String company, String project, String problem, Authority authority) {
            this.name = text(name, "Contact name", 120, false); this.email = PilotIntake.email(email);
            this.role = text(role, "Role", 120, false); this.company = text(company, "Company", 160, false);
            this.project = text(project, "Next real project", 1000, true); this.problem = text(problem, "Current problem", 1000, true);
            require(authority != null, "Choose an optional authority response."); this.authority = authority;
        }
    }
    static final class Lead {
        final String id, name, email, role, company, project, problem, consentVersion, consentNotice;
        final Authority authority;
        final Host host;
        final long createdAt, updatedAt, consentedAt, revokedAt;
        Lead(String id, Draft d, Host host, long created, long updated, long consented, long revoked, String version, String wording) {
            this.id = id; this.host = host; createdAt = created; updatedAt = updated; consentedAt = consented; revokedAt = revoked;
            consentVersion = version; consentNotice = wording;
            name = d == null ? "" : d.name; email = d == null ? "" : d.email; role = d == null ? "" : d.role; company = d == null ? "" : d.company;
            project = d == null ? "" : d.project; problem = d == null ? "" : d.problem; authority = d == null ? Authority.NOT_STATED : d.authority;
        }
        boolean active() { return revokedAt == 0; }
        JSONObject json() throws Exception {
            return new JSONObject().put("id",id).put("name",name).put("email",email).put("role",role).put("company",company)
                    .put("nextRealProject",project).put("currentProblem",problem).put("selfReportedAuthority",authority.name())
                    .put("interestStatus",INTEREST_ONLY).put("host",host.json()).put("createdAtEpochMillis",createdAt).put("updatedAtEpochMillis",updatedAt)
                    .put("consentedAtEpochMillis",consentedAt).put("revokedAtEpochMillis",revokedAt).put("consentVersion",consentVersion).put("consentNotice",consentNotice);
        }
    }
    static final class Notebook {
        final Host host;
        final List<Lead> leads;
        Notebook() { this(null, Collections.emptyList()); }
        private Notebook(Host host, List<Lead> leads) { this.host = host; this.leads = Collections.unmodifiableList(new ArrayList<>(leads)); }
        Lead find(String id) { for (Lead l : leads) if (l.id.equals(id)) return l; return null; }
        int activeCount() { int n=0; for (Lead l:leads) if(l.active()) n++; return n; }
        Notebook withHost(Host next) {
            require(next != null, "Demo host details are required.");
            require(activeCount() == 0 || (host != null && host.same(next)), "Active permissions belong to the current demo host. Revoke or delete those records before changing the recipient.");
            return new Notebook(next, leads);
        }
        Notebook optIn(String existingId, Draft draft, boolean affirmativeConsent, long now) {
            require(affirmativeConsent, "Explicit permission to follow up about a PAID pilot is required. Nothing was saved.");
            require(host != null && draft != null, "Demo host and contact details are required."); clock(now);
            Lead previous = existingId == null ? null : find(existingId);
            require(existingId == null || (previous != null && previous.active()), "This active record no longer exists. Create a new opt-in instead.");
            require(previous == null || now >= previous.updatedAt, "Device clock is earlier than the saved record.");
            for (Lead l : leads) require(!l.active() || l.id.equals(existingId) || !l.email.equalsIgnoreCase(draft.email), "An active contact already exists. Use Edit with renewed permission instead.");
            require(previous != null || leads.size() < MAX_RECORDS, "Notebook is full (100 records). Delete records deliberately before adding more.");
            Lead next = new Lead(previous == null ? UUID.randomUUID().toString() : previous.id, draft, host,
                    previous == null ? now : previous.createdAt, now, now, 0, CONSENT_VERSION, notice(host));
            List<Lead> copy = new ArrayList<>(leads); if(previous != null) copy.remove(previous); copy.add(next); return new Notebook(host,copy);
        }
        Notebook revoke(String id, long now) {
            Lead old=find(id); require(old != null, "Record no longer exists."); if(!old.active()) return this;
            clock(now); require(now >= old.updatedAt, "Device clock is earlier than the saved record.");
            Lead redacted=new Lead(old.id,null,old.host,old.createdAt,now,old.consentedAt,now,old.consentVersion,old.consentNotice);
            List<Lead> copy=new ArrayList<>(leads); copy.set(copy.indexOf(old),redacted); return new Notebook(host,copy);
        }
        Notebook delete(String id) { require(find(id)!=null,"Record no longer exists.");List<Lead> copy=new ArrayList<>(leads);copy.removeIf(l->l.id.equals(id));return new Notebook(host,copy); }
    }
    static String encode(Notebook n) throws Exception {
        JSONObject r=new JSONObject().put("format","cinex-pilot-local").put("version",1).put("host",n.host==null?JSONObject.NULL:n.host.json());
        JSONArray a=new JSONArray();for(Lead l:n.leads)a.put(l.json());r.put("records",a);String s=r.toString(2);require(s.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES,"Notebook exceeds 256 KiB. No existing data was replaced.");return s;
    }
    static Notebook decode(String s) throws Exception {
        try {
            JSONObject r=ItemCatalog.object(s); require("cinex-pilot-local".equals(r.getString("format")) && integer(r,"version")==1,"Unsupported pilot notebook format.");
            Host h=r.isNull("host")?null:host(r.getJSONObject("host"));JSONArray rows=r.getJSONArray("records");require(rows.length()<=MAX_RECORDS,"Too many pilot records.");
            List<Lead> leads=new ArrayList<>();Set<String> ids=new HashSet<>(),emails=new HashSet<>();
            for(int i=0;i<rows.length();i++) {
                JSONObject j=rows.getJSONObject(i);String id=j.getString("id");require(UUID.fromString(id).toString().equals(id),"Invalid pilot record ID.");require(ids.add(id),"Duplicate pilot record ID.");
                Host who=host(j.getJSONObject("host"));long created=integer(j,"createdAtEpochMillis"),updated=integer(j,"updatedAtEpochMillis"),consented=integer(j,"consentedAtEpochMillis"),revoked=integer(j,"revokedAtEpochMillis");
                clock(created);clock(updated);clock(consented);require(updated>=consented&&consented>=created&&revoked>=0&&(revoked==0||revoked>=consented&&updated>=revoked),"Invalid pilot event times.");
                require(CONSENT_VERSION.equals(j.getString("consentVersion"))&&notice(who).equals(j.getString("consentNotice"))&&INTEREST_ONLY.equals(j.getString("interestStatus")),"Unrecognized consent wording or interest status.");
                Draft d=null;
                if(revoked==0) {
                    require(h!=null&&h.same(who),"Active permission belongs to a different demo host.");
                    d=new Draft(j.getString("name"),j.getString("email"),j.getString("role"),j.getString("company"),j.getString("nextRealProject"),j.getString("currentProblem"),Authority.valueOf(j.getString("selfReportedAuthority")));
                    require(emails.add(d.email.toLowerCase(Locale.ROOT)),"Duplicate active pilot contact.");
                } else {
                    for(String key:new String[]{"name","email","role","company","nextRealProject","currentProblem"})require(j.getString(key).isEmpty(),"Revoked record contains contact data.");
                    require(Authority.NOT_STATED.name().equals(j.getString("selfReportedAuthority")),"Revoked record contains authority data.");
                }
                leads.add(new Lead(id,d,who,created,updated,consented,revoked,CONSENT_VERSION,notice(who)));
            }
            return new Notebook(h,leads);
        } catch(Validation e) { throw e; } catch(Exception e) { throw new Validation("Pilot notebook is malformed. It has not been reset or overwritten."); }
    }
    static String exportJson(Notebook n, long now) throws Exception {
        clock(now);JSONObject r=new JSONObject().put("format","cinex-consented-pilot-interest").put("version",1).put("interestStatus",INTEREST_ONLY)
                .put("exportedAtEpochMillis",now).put("clockSource","device clock, not server-verified");JSONArray a=new JSONArray();for(Lead l:n.leads)if(l.active())a.put(l.json());
        require(a.length()>0,"No currently consented contacts to export.");r.put("consentedContacts",a);return r.toString(2);
    }
    static String exportCsv(Notebook n) {
        require(n.activeCount()>0,"No currently consented contacts to export.");
        StringBuilder s=new StringBuilder();String[] header={"interest_status","contact_name","email","company","role","self_reported_authority","next_real_project","current_problem","consented_at_Europe_Tallinn","consent_version","consent_notice","demo_host_name","demo_host_contact"};row(s,header);
        for(Lead l:n.leads)if(l.active())row(s,new String[]{INTEREST_ONLY,l.name,l.email,l.company,l.role,l.authority.name(),l.project,l.problem,humanTime(l.consentedAt),l.consentVersion,l.consentNotice,l.host.name,l.host.contact});return s.toString();
    }
    private static void row(StringBuilder s,String[] cells){for(int i=0;i<cells.length;i++){if(i>0)s.append(',');s.append(csvCell(cells[i]));}s.append("\r\n");}
    static String csvCell(String value) {
        int i=0;while(i<value.length()&&(Character.isWhitespace(value.charAt(i))||Character.isSpaceChar(value.charAt(i))))i++;
        boolean dangerous=i<value.length()&&"=+-@".indexOf(value.charAt(i))>=0;
        if(!value.isEmpty()&&"\t\r\n".indexOf(value.charAt(0))>=0)dangerous=true;
        return "\""+(dangerous?"'":"")+value.replace("\"","\"\"")+"\"";
    }
    static String humanTime(long time){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z 'Europe/Tallinn'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Europe/Tallinn"));return f.format(new Date(time));}
    private static Host host(JSONObject j)throws Exception{return new Host(j.getString("name"),j.getString("contact"));}
    private static long integer(JSONObject j,String k)throws Exception{Object v=j.get(k);require(v instanceof Number,"Numeric event metadata required.");double d=((Number)v).doubleValue();require(Double.isFinite(d)&&d==Math.rint(d),"Invalid numeric event metadata.");return ((Number)v).longValue();}
    private static void clock(long t){require(t>0&&t<=4102444800000L,"Check the device clock before recording permission.");}
    private static String text(String value,String label,int max,boolean required){
        require(value!=null,label+" is required.");String s=value.trim();require((!required||!s.isEmpty())&&s.length()<=max,label+" must be "+(required?"1–":"0–")+max+" characters.");
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){require(i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1)),"Invalid Unicode in "+label+".");i++;continue;}require(!Character.isLowSurrogate(c),"Invalid Unicode in "+label+".");require((!Character.isISOControl(c)||c=='\n'||c=='\r'||c=='\t')&&Character.getType(c)!=Character.FORMAT,"Remove control/invisible characters from "+label+".");}return s;
    }
    private static String email(String s){String e=text(s,"Email",254,true);require(e.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}")&&!e.startsWith(".")&&!e.substring(0,e.indexOf('@')).endsWith(".")&&!e.contains(".."),"Enter an ordinary email address without spaces. Address ownership is not verified.");return e;}
}
