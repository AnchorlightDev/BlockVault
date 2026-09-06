package me.benrobson.blockvault.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

public class FileUtil {

    private final Plugin plugin;
    private final File configFile;
    private YamlConfiguration config;

    public FileUtil(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        reloadConfig(); // Load the configuration initially
    }

    /**
     * Returns the plugin's configuration.
     *
     * @return The plugin's configuration.
     */
    public YamlConfiguration getConfig() {
        return config;
    }

    /**
     * Saves the current state of the configuration to the file.
     */
    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save config.yml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Reloads the configuration from the file.
     */
    public void reloadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false); // Ensure the default config exists
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);
    }

    /**
     * Updates a specific configuration value without erasing others.
     *
     * @param path  The configuration path.
     * @param value The value to set.
     */
    public void updateConfigValue(String path, Object value) {
        // Set the new value
        getConfig().set(path, value);
        // Save the config to the file
        saveConfig();
        // Reload the config to reflect changes immediately
        plugin.reloadConfig();
    }
}