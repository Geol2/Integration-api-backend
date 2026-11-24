package com.integration.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지구 근접 소행성 — NASA JPL SBDB Close-Approach Data API(cad.api)와 궤도 요소(sbdb.api).
 * 브라우저에서 바로 부르면 CORS로 막힐 수 있어 서버가 대신 받아 6시간 캐시합니다.
 * 키 필요 없음. 출처: NASA/JPL-Caltech Solar System Dynamics.
 */
@Service
public class NeoService {

    private static final Logger log = LoggerFactory.getLogger(NeoService.class);
    private static final String CAD = "https://ssd-api.jpl.nasa.gov/cad.api";
    private static final String SBDB = "https://ssd-api.jpl.nasa.gov/sbdb.api";
    private static final Duration TTL = Duration.ofHours(6);
    private static final Duration ORBIT_TTL = Duration.ofDays(3);
    private static final int MAX_ITEMS = 40;

    /** e·a·i·om·w·ma: 황도 J2000 기준 궤도 요소(AU·도), epoch: 율리우스일(TDB) */
    public record Orbit(double e, double a, double i, double om, double w, double ma, double epoch) {}

    /** 근접 하나. time: 최근접 시각(UTC 근사, ISO), distAu: 공칭 최소 거리, vRel: 상대 속도(km/s) */
    public record Approach(String des, String name, String time, double jd, double distAu, double distMinAu,
                           double vRel, Double h, Double diameterKm, boolean pha, Orbit orbit) {}

    public record NeoList(String updated, int pastDays, int futureDays, List<Approach> items) {}

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile NeoList cache;
    private volatile long cachedAt;
    private final Map<String, Object[]> orbitCache = new ConcurrentHashMap<>(); // des → {Orbit/null, pha, at}

    public NeoService(ObjectMapper json) {
        this.json = json;
    }

    public synchronized NeoList list() {
        if (cache != null && System.currentTimeMillis() - cachedAt < TTL.toMillis()) return cache;
        try {
            cache = fetch(7, 60);
            cachedAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("NEO 근접 자료를 받지 못함: {}", e.toString());
            if (cache == null) throw new IllegalStateException("NASA 근접 소행성 자료를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        return cache;
    }

    private NeoList fetch(int pastDays, int futureDays) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String url = CAD + "?date-min=" + today.minusDays(pastDays) + "&date-max=" + today.plusDays(futureDays)
                + "&dist-max=0.05&sort=dist&fullname=true&diameter=true";
        JsonNode root = get(url);
        List<String> fields = new ArrayList<>();
        root.path("fields").forEach(f -> fields.add(f.asText()));
        List<Approach> out = new ArrayList<>();
        for (JsonNode row : root.path("data")) {
            if (out.size() >= MAX_ITEMS) break;
            Map<String, String> r = new HashMap<>();
            for (int k = 0; k < fields.size() && k < row.size(); k++) r.put(fields.get(k), row.get(k).isNull() ? null : row.get(k).asText());
            String des = r.get("des");
            Object[] orb = orbit(des);
            out.add(new Approach(des, r.getOrDefault("fullname", des).strip(), r.get("cd"),
                    num(r.get("jd"), 0), num(r.get("dist"), 0), num(r.get("dist_min"), 0), num(r.get("v_rel"), 0),
                    numOrNull(r.get("h")), numOrNull(r.get("diameter")), (Boolean) orb[1], (Orbit) orb[0]));
        }
        return new NeoList(java.time.Instant.now().toString(), pastDays, futureDays, out);
    }

    /** 궤도 요소·PHA 여부 (3일 캐시). 실패해도 목록은 보여 주도록 null. */
    private Object[] orbit(String des) {
        Object[] c = orbitCache.get(des);
        if (c != null && System.currentTimeMillis() - (long) c[2] < ORBIT_TTL.toMillis()) return c;
        Orbit o = null;
        boolean pha = false;
        try {
            JsonNode root = get(SBDB + "?sstr=" + URLEncoder.encode(des, StandardCharsets.UTF_8));
            pha = root.path("object").path("pha").asBoolean(false);
            JsonNode orb = root.path("orbit");
            Map<String, Double> el = new HashMap<>();
            for (JsonNode e : orb.path("elements")) {
                String v = e.path("value").asText(null);
                if (v != null) try { el.put(e.path("name").asText(), Double.parseDouble(v)); } catch (NumberFormatException ignored) {}
            }
            double epoch = Double.parseDouble(orb.path("epoch").asText("0"));
            if (el.containsKey("e") && el.containsKey("a") && el.containsKey("ma"))
                o = new Orbit(el.get("e"), el.get("a"), el.getOrDefault("i", 0d), el.getOrDefault("om", 0d),
                        el.getOrDefault("w", 0d), el.get("ma"), epoch);
        } catch (Exception e) {
            log.debug("궤도 요소 실패 {}: {}", des, e.toString());
        }
        Object[] v = {o, pha, System.currentTimeMillis()};
        orbitCache.put(des, v);
        return v;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "momentum-universe/1.0").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("JPL " + res.statusCode());
        return json.readTree(res.body());
    }

    private static double num(String s, double d) { try { return s == null ? d : Double.parseDouble(s); } catch (NumberFormatException e) { return d; } }
    private static Double numOrNull(String s) { try { return s == null ? null : Double.parseDouble(s); } catch (NumberFormatException e) { return null; } }
}
