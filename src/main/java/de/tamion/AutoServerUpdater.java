package de.tamion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.papermc.paper.ServerBuildInfo;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalTime;
import java.util.logging.Level;

public final class AutoServerUpdater extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updateConfig();
        startDailyRestartTask();

        File serverJar = new File(System.getProperty("java.class.path"));
        ServerBuildInfo buildInfo = ServerBuildInfo.buildInfo();

        int currentBuild = buildInfo.buildNumber().isPresent() ? buildInfo.buildNumber().getAsInt() : -1;
        String mcVersion = buildInfo.minecraftVersionId();
        String brand = buildInfo.brandName().toLowerCase();
        String pluginVersion = getDescription().getVersion();
        String userAgent = getConfig().getString("user-agent", "AutoServerUpdater/" + pluginVersion + " (contact@tamion.de)");

        try {
            String downloadUrl = null;
            int highestBuild = -1;

            if (brand.contains("paper")) {
                JsonNode root = fetchJson("https://fill.papermc.io/v3/projects/paper/versions/" + mcVersion + "/builds", userAgent);
                if (root != null && root.isArray() && root.size() > 0) {
                    JsonNode best = null;
                    for (JsonNode node : root) {
                        int id = node.get("id").asInt();
                        if (id > highestBuild) { highestBuild = id; best = node; }
                    }
                    if (highestBuild > currentBuild && best != null) {
                        downloadUrl = best.get("downloads").get("server:default").get("url").asText();
                    }
                }
            } else if (brand.contains("purpur")) {
                JsonNode root = fetchJson("https://api.purpurmc.org/v2/purpur/" + mcVersion + "/latest", userAgent);
                if (root != null && root.has("build")) {
                    highestBuild = root.get("build").asInt();
                    if (highestBuild > currentBuild) {
                        downloadUrl = "https://api.purpurmc.org/v2/purpur/" + mcVersion + "/" + highestBuild + "/download";
                    }
                }
            } else if (brand.contains("leaf")) {
                JsonNode root = fetchJson("https://api.leafmc.one/v2/projects/leaf/versions/" + mcVersion + "/builds", userAgent);
                if (root != null && root.has("builds")) {
                    for (JsonNode b : root.get("builds")) {
                        int bNum = b.get("build").asInt();
                        if (bNum > highestBuild) highestBuild = bNum;
                    }
                    if (highestBuild > currentBuild) {
                        downloadUrl = String.format("https://api.leafmc.one/v2/projects/leaf/versions/%s/builds/%d/downloads/leaf-%s-%d.jar",
                                mcVersion, highestBuild, mcVersion, highestBuild);
                    }
                }
            }

            if (downloadUrl != null) {
                getLogger().info("New build detected: " + highestBuild + " (Current: " + currentBuild + ")");
                getLogger().info("Downloading update...");
                downloadFile(downloadUrl, serverJar, userAgent);
                executeAction();
            } else {
                if (!getConfig().getBoolean("daily-restart.enabled", false)) {
                    getLogger().info("No update found and daily restart is disabled. Disabling plugin.");
                    Bukkit.getPluginManager().disablePlugin(this);
                }
            }

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Update check failed: " + e.getMessage());
        }
    }

    private void updateConfig() {
        boolean changed = false;
        String currentVersion = getDescription().getVersion();

        if (!getConfig().contains("update-action")) {
            getConfig().set("update-action", "STOP");
            changed = true;
        }
        if (!getConfig().contains("user-agent")) {
            getConfig().set("user-agent", "AutoServerUpdater/" + currentVersion + " (contact@tamion.de)");
            changed = true;
        }
        if (!getConfig().contains("daily-restart.enabled")) {
            getConfig().set("daily-restart.enabled", false);
            changed = true;
        }
        if (!getConfig().contains("daily-restart.time")) {
            getConfig().set("daily-restart.time", "04:00");
            changed = true;
        }

        if (changed) saveConfig();
    }

    private void startDailyRestartTask() {
        if (!getConfig().getBoolean("daily-restart.enabled", false)) return;
        try {
            LocalTime target = LocalTime.parse(getConfig().getString("daily-restart.time", "04:00"));
            long delay = Duration.between(LocalTime.now(), target).toSeconds();
            if (delay < 0) delay += 86400;
            Bukkit.getScheduler().runTaskTimer(this, this::executeAction, delay * 20L, 1728000L);
        } catch (Exception ignored) {}
    }

    private void executeAction() {
        String action = getConfig().getString("update-action", "RESTART").toUpperCase();
        if (action.equals("STOP")) Bukkit.shutdown();
        else Bukkit.getServer().spigot().restart();
    }

    private JsonNode fetchJson(String url, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "application/json");
        if (c.getResponseCode() != 200) return null;
        try (InputStream in = c.getInputStream()) { return new ObjectMapper().readTree(in); }
    }

    private void downloadFile(String url, File target, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) { FileUtils.copyInputStreamToFile(in, target); }
    }
}