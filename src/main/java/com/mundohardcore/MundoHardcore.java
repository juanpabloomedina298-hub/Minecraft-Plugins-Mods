package com.mundohardcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Cuando alguien muere crea un mundo hardcore nuevo (con Nether y End),
 * mueve a todos los jugadores sin sacarlos del servidor y borra el mundo anterior.
 */
public final class MundoHardcore extends JavaPlugin implements Listener {

    private static final String PREFIJO = "mundo_";

    private File archivoEstado;
    private YamlConfiguration estado;
    private int contador;
    /** Nombre base del grupo de mundos actual. null = todavía se usa el mundo principal del servidor. */
    private String base;
    private boolean creando = false;
    /** Dónde murió cada jugador mientras espera a que se cree el mundo nuevo. */
    private final Map<UUID, Location> lugaresDeMuerte = new HashMap<>();

    // ------------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------------

    @Override
    public void onLoad() {
        // Se ejecuta antes de que el servidor cargue sus mundos: aquí se pueden
        // borrar las carpetas pendientes, incluido el mundo principal.
        getDataFolder().mkdirs();
        archivoEstado = new File(getDataFolder(), "estado.yml");
        estado = YamlConfiguration.loadConfiguration(archivoEstado);
        contador = estado.getInt("contador", 0);
        base = estado.getString("actual", null);

        List<String> pendientes = estado.getStringList("pendientes");
        if (!pendientes.isEmpty()) {
            for (String ruta : pendientes) {
                borrarSeguro(new File(ruta));
            }
            estado.set("pendientes", new ArrayList<String>());
            guardar();
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Los mundos creados por plugins no se cargan solos al reiniciar.
        if (base != null && !base.equals(Bukkit.getWorlds().get(0).getName())) {
            try {
                long semilla = new Random().nextLong();
                crearMundo(base, World.Environment.NORMAL, semilla);
                crearMundo(base + "_nether", World.Environment.NETHER, semilla);
                crearMundo(base + "_the_end", World.Environment.THE_END, semilla);
            } catch (Exception ex) {
                getLogger().severe("No se pudo cargar el mundo actual (" + base + "): " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    /** /seedreset: lo puede usar cualquiera. Borra el mundo actual y crea uno nuevo al instante. */
    @Override
    public boolean onCommand(CommandSender sender, Command comando, String etiqueta, String[] args) {
        if (!comando.getName().equalsIgnoreCase("seedreset")) {
            return false;
        }
        if (creando) {
            sender.sendMessage(Component.text("Ya se está creando un mundo nuevo, espera un momento.", NamedTextColor.RED));
            return true;
        }
        creando = true;
        Bukkit.getServer().sendMessage(Component.text(
                sender.getName() + " usó /seedreset: creando un mundo nuevo...", NamedTextColor.YELLOW));
        // Siguiente tick: sin los 10 segundos de espera de la muerte.
        Bukkit.getScheduler().runTask(this, this::cambiarMundo);
        return true;
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    @EventHandler
    public void alMorir(PlayerDeathEvent e) {
        Player p = e.getEntity();
        lugaresDeMuerte.put(p.getUniqueId(), p.getLocation().clone());

        // Reaparece solo (sin pantalla de muerte) como espectador, justo donde murió.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && p.isDead()) {
                p.spigot().respawn();
            }
        }, 1L);

        if (creando) {
            return;
        }
        creando = true;
        // 200 ticks = 10 segundos de espera antes de crear el mundo nuevo.
        Bukkit.getScheduler().runTaskLater(this, this::cambiarMundo, 200L);
    }

    @EventHandler
    public void alReaparecer(PlayerRespawnEvent e) {
        Player jugador = e.getPlayer();

        // Durante la espera: espectador justo donde murió.
        Location lugar = lugaresDeMuerte.remove(jugador.getUniqueId());
        if (lugar != null && lugar.getWorld() != null && creando) {
            e.setRespawnLocation(lugar);
            Bukkit.getScheduler().runTask(this, () -> jugador.setGameMode(GameMode.SPECTATOR));
            return;
        }

        if (base == null) {
            return;
        }
        World mundo = Bukkit.getWorld(base);
        if (mundo == null) {
            return;
        }
        e.setRespawnLocation(mundo.getSpawnLocation());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.SURVIVAL);
            }
        });
    }

    @EventHandler
    public void alEntrar(PlayerJoinEvent e) {
        if (base == null) {
            return;
        }
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            World actual = Bukkit.getWorld(base);
            if (actual == null) {
                return;
            }
            String w = p.getWorld().getName();
            boolean enGrupo = w.equals(base) || w.equals(base + "_nether") || w.equals(base + "_the_end");
            if (!enGrupo) {
                p.teleport(actual.getSpawnLocation());
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    p.setGameMode(GameMode.SURVIVAL);
                }
            }
        });
    }

    @EventHandler
    public void alUsarPortal(PlayerPortalEvent e) {
        if (base == null) {
            return;
        }
        World overworld = Bukkit.getWorld(base);
        World nether = Bukkit.getWorld(base + "_nether");
        World end = Bukkit.getWorld(base + "_the_end");
        if (overworld == null || nether == null || end == null) {
            return;
        }
        Location desde = e.getFrom();
        World origen = desde.getWorld();
        if (origen == null) {
            return;
        }

        switch (e.getCause()) {
            case NETHER_PORTAL -> {
                if (origen.equals(overworld)) {
                    e.setCanCreatePortal(true);
                    e.setTo(new Location(nether, desde.getX() / 8.0, desde.getY(), desde.getZ() / 8.0,
                            desde.getYaw(), desde.getPitch()));
                } else if (origen.equals(nether)) {
                    e.setCanCreatePortal(true);
                    e.setTo(new Location(overworld, desde.getX() * 8.0, desde.getY(), desde.getZ() * 8.0,
                            desde.getYaw(), desde.getPitch()));
                }
            }
            case END_PORTAL -> {
                if (origen.equals(overworld)) {
                    e.setTo(end.getSpawnLocation());
                } else if (origen.equals(end)) {
                    e.setTo(overworld.getSpawnLocation());
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // Cambio de mundo
    // ------------------------------------------------------------------

    private void cambiarMundo() {
        try {
            String viejo = (base != null) ? base : Bukkit.getWorlds().get(0).getName();

            do {
                contador++;
            } while (Bukkit.getWorld(PREFIJO + contador) != null
                    || new File(Bukkit.getWorldContainer(), PREFIJO + contador).exists());
            String nuevo = PREFIJO + contador;

            long semilla = new Random().nextLong();
            World overworld = crearMundo(nuevo, World.Environment.NORMAL, semilla);
            crearMundo(nuevo + "_nether", World.Environment.NETHER, semilla);
            crearMundo(nuevo + "_the_end", World.Environment.THE_END, semilla);

            base = nuevo;
            estado.set("actual", base);
            estado.set("contador", contador);
            guardar();

            // Ya no hay que dejar a nadie donde murió: todos van al mundo nuevo.
            lugaresDeMuerte.clear();

            // Se mueve a todos sin expulsar a nadie. Los que esperaban de espectador
            // vuelven a supervivencia en el mundo nuevo (ver reiniciar).
            Location destino = overworld.getSpawnLocation();
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (p.isDead()) {
                    p.spigot().respawn();
                } else {
                    p.teleport(destino);
                }
                reiniciar(p);
            }

            borrarGrupo(viejo);
        } catch (Exception ex) {
            getLogger().severe("No se pudo crear el mundo nuevo: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            creando = false;
        }
    }

    private World crearMundo(String nombre, World.Environment entorno, long semilla) {
        WorldCreator creador = new WorldCreator(nombre);
        creador.environment(entorno);
        creador.seed(semilla);
        creador.hardcore(true);
        World mundo = creador.createWorld();
        if (mundo == null) {
            throw new IllegalStateException("createWorld devolvió null para " + nombre);
        }
        mundo.setDifficulty(Difficulty.HARD);
        mundo.setHardcore(true);
        return mundo;
    }

    private void reiniciar(Player p) {
        AttributeInstance vida = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(vida != null ? vida.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExhaustion(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setLevel(0);
        p.setExp(0f);
        for (PotionEffect efecto : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(efecto.getType());
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    // ------------------------------------------------------------------
    // Borrado de mundos
    // ------------------------------------------------------------------

    private void borrarGrupo(String viejo) {
        String[] nombres = {viejo, viejo + "_nether", viejo + "_the_end"};
        World principal = Bukkit.getWorlds().get(0);
        boolean esPrincipal = viejo.equals(principal.getName());

        List<String> pendientes = new ArrayList<>(estado.getStringList("pendientes"));
        List<File> ahora = new ArrayList<>();

        for (String nombre : nombres) {
            World mundo = Bukkit.getWorld(nombre);
            if (mundo == null) {
                continue;
            }
            File carpeta = mundo.getWorldFolder();
            if (esPrincipal) {
                // El mundo principal no se puede descargar con el servidor encendido:
                // se borra en el próximo arranque, antes de que se cargue.
                pendientes.add(carpeta.getAbsolutePath());
            } else if (Bukkit.unloadWorld(mundo, false)) {
                ahora.add(carpeta);
            } else {
                getLogger().warning("No se pudo descargar " + nombre + ", se borrará al reiniciar.");
                pendientes.add(carpeta.getAbsolutePath());
            }
        }

        estado.set("pendientes", new ArrayList<>(new LinkedHashSet<>(pendientes)));
        guardar();

        if (!ahora.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                for (File carpeta : ahora) {
                    borrarSeguro(carpeta);
                }
            });
        }
    }

    /** Solo borra carpetas que estén dentro de la carpeta de mundos del servidor. */
    private void borrarSeguro(File carpeta) {
        try {
            Path contenedor = Bukkit.getWorldContainer().getCanonicalFile().toPath();
            Path ruta = carpeta.getCanonicalFile().toPath();
            if (!ruta.startsWith(contenedor) || ruta.equals(contenedor)) {
                getLogger().warning("Ruta fuera de la carpeta de mundos, no se borra: " + ruta);
                return;
            }
            if (!Files.exists(ruta)) {
                return;
            }
            try (Stream<Path> recorrido = Files.walk(ruta)) {
                recorrido.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ex) {
                        getLogger().warning("No se pudo borrar " + p + ": " + ex.getMessage());
                    }
                });
            }
        } catch (IOException ex) {
            getLogger().warning("Error al borrar " + carpeta + ": " + ex.getMessage());
        }
    }

    private void guardar() {
        try {
            estado.save(archivoEstado);
        } catch (IOException ex) {
            getLogger().severe("No se pudo guardar estado.yml: " + ex.getMessage());
        }
    }
}