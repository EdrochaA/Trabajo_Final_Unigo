package com.example.unigo.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unigo.R;
import com.example.unigo.model.ParadaBus;

import org.osmdroid.bonuspack.routing.OSRMRoadManager;
import org.osmdroid.bonuspack.routing.Road;
import org.osmdroid.bonuspack.routing.RoadManager;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

public class BusActivity extends AppCompatActivity {

    private MapView map;
    private GeoPoint campus = new GeoPoint(42.8467, -2.6731);
    private GeoPoint ubicacionAleatoria;
    private List<ParadaBus> todasLasParadas = new ArrayList<>();
    private Map<String, List<Integer>> tripStops = new HashMap<>();

    private TextView tvInfo;
    private LinearLayout listaParadas;
    private ScrollView panelRuta;
    private ImageButton btnToggle;
    private boolean infoExpandida = false;


    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        setContentView(R.layout.activity_bus);

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.ALWAYS);

        tvInfo = findViewById(R.id.tv_info);
        listaParadas = findViewById(R.id.lista_paradas);
        panelRuta = findViewById(R.id.panel_ruta);
        btnToggle = findViewById(R.id.btn_toggle_panel);

        map.setOnTouchListener((v, event) -> {
            if (panelRuta.getVisibility() == View.VISIBLE) {
                panelRuta.animate()
                        .translationX(panelRuta.getWidth())
                        .setDuration(300)
                        .withEndAction(() -> panelRuta.setVisibility(View.GONE))
                        .start();
                return true; // Consume el evento para que no lo pase al mapa
            }
            return false;
        });

        btnToggle.setOnClickListener(v -> {
            if (panelRuta.getVisibility() == View.GONE) {
                panelRuta.setVisibility(View.VISIBLE);
                panelRuta.animate()
                        .translationX(0)
                        .setDuration(300)
                        .start();
            } else {
                panelRuta.animate()
                        .translationX(panelRuta.getWidth())
                        .setDuration(300)
                        .withEndAction(() -> panelRuta.setVisibility(View.GONE))
                        .start();
            }
        });


        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        ubicacionAleatoria = generarUbicacionDentroVitoria();
        map.getController().setZoom(14.5);
        map.getController().setCenter(ubicacionAleatoria);

        mostrarUbicacionSimulada();
        marcarCampusAlava();

        new Thread(() -> {
            cargarParadas();
            cargarStopTimes();

            ParadaBus origen = encontrarParadaCercanaConRutaACampus();
            if (origen == null) {
                runOnUiThread(() -> Toast.makeText(this, "No hay ruta al campus", Toast.LENGTH_LONG).show());
                return;
            }

            int campusId = encontrarStopIdPorGeoPoint(campus);
            List<Integer> stopsRuta = buscarRutaBus(origen.getStopId(), campusId);
            if (stopsRuta == null) {
                runOnUiThread(() -> Toast.makeText(this, "Ruta en bus no encontrada", Toast.LENGTH_SHORT).show());
                return;
            }

            ParadaBus ultima = getParadaById(stopsRuta.get(stopsRuta.size() - 1));

            runOnUiThread(() -> {
                mostrarParada(origen, "Parada origen");
                mostrarParada(ultima, "Última parada");
                mostrarParadasEnLista(stopsRuta);
            });

            double[] distancias = new double[3];
            int[] tiempos = new int[3];

            distancias[0] = ubicacionAleatoria.distanceToAsDouble(origen.getGeoPoint()) / 1000.0;
            tiempos[0] = (int) Math.round(distancias[0] / 5.0 * 60);

            distancias[1] = calcularDistanciaBus(stopsRuta);
            tiempos[1] = (int) Math.round(distancias[1] / 40.0 * 60); // Suponemos media de 40km/h

            distancias[2] = ultima.getGeoPoint().distanceToAsDouble(campus) / 1000.0;
            tiempos[2] = (int) Math.round(distancias[2] / 5.0 * 60);

            runOnUiThread(() -> {
                tvInfo.setText(String.format(
                        Locale.getDefault(),
                        "🟢 A pie hasta '%s': %.2f km (%d min)\n🚌 En bus: %.2f km (%d min)\n🔴 A pie al campus: %.2f km (%d min)",
                        origen.getStopName(), distancias[0], tiempos[0],
                        distancias[1], tiempos[1],
                        distancias[2], tiempos[2]
                ));
                tvInfo.setMaxLines(3); // Estado inicial
                tvInfo.setOnClickListener(v -> {
                    if (infoExpandida) {
                        tvInfo.setMaxLines(3);
                        infoExpandida = false;
                    } else {
                        tvInfo.setMaxLines(Integer.MAX_VALUE); // Mostrar todo
                        infoExpandida = true;
                    }
                });
                tvInfo.setVisibility(View.VISIBLE);
                drawWalkingRoute(ubicacionAleatoria, origen.getGeoPoint());
                mostrarRutaBus(stopsRuta);
                drawWalkingRoute(ultima.getGeoPoint(), campus);
            });
        }).start();
    }

    private void mostrarParadasEnLista(List<Integer> ids) {
        listaParadas.removeViews(1, listaParadas.getChildCount() - 1);
        for (int id : ids) {
            ParadaBus p = getParadaById(id);
            if (p != null) {
                TextView t = new TextView(this);
                t.setText("• " + p.getStopName());
                t.setTextSize(16f);
                t.setPadding(8, 8, 8, 8);
                listaParadas.addView(t);
            }
        }
    }

    private double calcularDistanciaBus(List<Integer> ids) {
        double total = 0;
        for (int i = 0; i < ids.size() - 1; i++) {
            ParadaBus a = getParadaById(ids.get(i));
            ParadaBus b = getParadaById(ids.get(i + 1));
            if (a != null && b != null)
                total += a.getGeoPoint().distanceToAsDouble(b.getGeoPoint());
        }
        return total / 1000.0;
    }

    private List<Integer> buscarRutaBus(int origenId, int destinoId) {
        for (List<Integer> stops : tripStops.values()) {
            if (stops.contains(origenId) && stops.contains(destinoId)) {
                int i1 = stops.indexOf(origenId);
                int i2 = stops.indexOf(destinoId);
                if (i1 < i2) return stops.subList(i1, i2 + 1);
            }
        }
        return null;
    }

    private void cargarParadas() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("stops.txt")))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length >= 7) {
                    int id = Integer.parseInt(p[0]);
                    String name = p[2];
                    double lat = Double.parseDouble(p[5]);
                    double lon = Double.parseDouble(p[6]);
                    todasLasParadas.add(new ParadaBus(id, name, lat, lon));
                }
            }
        } catch (Exception e) {
            Log.e("BUS", "Error leyendo stops.txt", e);
        }
    }

    private void cargarStopTimes() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("stop_times.txt")))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length >= 4) {
                    String tripId = p[0];
                    int stopId = Integer.parseInt(p[3]);
                    tripStops.computeIfAbsent(tripId, k -> new ArrayList<>()).add(stopId);
                }
            }
        } catch (Exception e) {
            Log.e("BUS", "Error leyendo stop_times.txt", e);
        }
    }

    private void mostrarRutaBus(List<Integer> ids) {
        List<GeoPoint> puntos = new ArrayList<>();
        for (int id : ids) {
            ParadaBus p = getParadaById(id);
            if (p != null) puntos.add(p.getGeoPoint());
        }
        Polyline linea = new Polyline();
        linea.setPoints(puntos);
        linea.setColor(Color.rgb(0, 0, 139));
        linea.setWidth(6f);
        map.getOverlays().add(linea);
        map.invalidate();
    }

    private void drawWalkingRoute(GeoPoint start, GeoPoint end) {
        new Thread(() -> {
            RoadManager roadManager = new OSRMRoadManager(this, "UNIGO");
            ((OSRMRoadManager) roadManager).setMean(OSRMRoadManager.MEAN_BY_FOOT);
            Road road = roadManager.getRoad(new ArrayList<>(Arrays.asList(start, end)));

            runOnUiThread(() -> {
                if (road.mStatus != Road.STATUS_OK) return;
                Polyline overlay = RoadManager.buildRoadOverlay(road);
                Paint p = overlay.getPaint();
                p.setColor(Color.RED);
                p.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
                p.setStrokeWidth(8f);
                map.getOverlays().add(overlay);
                map.invalidate();
            });
        }).start();
    }

    private int encontrarStopIdPorGeoPoint(GeoPoint p) {
        double min = Double.MAX_VALUE;
        ParadaBus mejor = null;
        for (ParadaBus parada : todasLasParadas) {
            double d = p.distanceToAsDouble(parada.getGeoPoint());
            if (d < min) {
                min = d;
                mejor = parada;
            }
        }
        return mejor != null ? mejor.getStopId() : -1;
    }

    private ParadaBus encontrarParadaCercanaConRutaACampus() {
        ParadaBus mejor = null;
        int campusId = encontrarStopIdPorGeoPoint(campus);
        double min = Double.MAX_VALUE;
        for (ParadaBus p : todasLasParadas) {
            for (List<Integer> stops : tripStops.values()) {
                if (stops.contains(p.getStopId()) && stops.contains(campusId)) {
                    double dist = ubicacionAleatoria.distanceToAsDouble(p.getGeoPoint());
                    if (dist < min) {
                        min = dist;
                        mejor = p;
                    }
                }
            }
        }
        return mejor;
    }

    private void mostrarParada(ParadaBus parada, String titulo) {
        Marker marker = new Marker(map);
        marker.setPosition(parada.getGeoPoint());
        marker.setTitle(titulo + ": " + parada.getStopName());
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setIcon(getResources().getDrawable(R.drawable.ic_parada, getTheme()));
        map.getOverlays().add(marker);
    }

    private void marcarCampusAlava() {
        Marker marker = new Marker(map);
        marker.setPosition(campus);
        marker.setTitle("Campus UPV/EHU");
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setIcon(getResources().getDrawable(R.drawable.ic_campus_red, getTheme()));
        map.getOverlays().add(marker);
    }

    private void mostrarUbicacionSimulada() {
        Marker marker = new Marker(map);
        marker.setPosition(ubicacionAleatoria);
        marker.setTitle("Ubicación simulada");
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(marker);
    }

    private GeoPoint generarUbicacionDentroVitoria() {
        double minLat = 42.82, maxLat = 42.87;
        double minLon = -2.71, maxLon = -2.64;
        double lat = minLat + Math.random() * (maxLat - minLat);
        double lon = minLon + Math.random() * (maxLon - minLon);
        return new GeoPoint(lat, lon);
    }

    private ParadaBus getParadaById(int id) {
        for (ParadaBus p : todasLasParadas) {
            if (p.getStopId() == id) return p;
        }
        return null;
    }
}
