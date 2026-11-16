package fun.megalev.events;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;

public final class MegaLevEvents extends JavaPlugin implements Listener, CommandExecutor {
    private final Random random = new Random();
    private final Map<UUID, Long> parkourRewards = new HashMap<>();
    private final Map<UUID, Long> shulkerExpiry = new HashMap<>();
    private final Set<UUID> eventGlowing = new HashSet<>();
    private final Set<UUID> usedParkourFinishes = new HashSet<>();
    private NamespacedKey keyTag, boxTag;
    private World world;
    private Villager npc;
    private int npcHits = 0;
    private EventArea vant, fever, casino;
    private UUID keyHolder;
    private long keyOutsideSince = -1;
    private boolean vantOpen;
    private int waves;
    private BukkitTask tickTask;
    private BukkitTask vantWaveTask;
    private boolean keyDestroyedOnEscape = false;
    private final Map<UUID, Inventory> boxInventories = new HashMap<>();

    private static class EventArea {
        final String type;
        final Location center;
        final long endsAt;
        final List<Location> blocks = new ArrayList<>();
        EventArea(String type, Location center, long durationSeconds) {
            this.type=type; this.center=center; this.endsAt=System.currentTimeMillis()+durationSeconds*1000;
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        keyTag=new NamespacedKey(this,"vant_key");
        boxTag=new NamespacedKey(this,"parkour_box");
        world=Bukkit.getWorld(getConfig().getString("world","anarchy"));
        if(world==null) { getLogger().severe("Мир из config.yml не найден. Плагин отключён."); Bukkit.getPluginManager().disablePlugin(this); return; }
        Bukkit.getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("event")).setExecutor(this);
        Objects.requireNonNull(getCommand("parkur")).setExecutor(this);
        restoreCooldowns();
        if(getConfig().getBoolean("npc.enabled")) spawnNpc();
        tickTask=Bukkit.getScheduler().runTaskTimer(this,this::tick,20L,20L);
    }
    @Override public void onDisable() {
        if(tickTask!=null) tickTask.cancel();
        stopAll();
        if(npc!=null && npc.isValid()) npc.remove();
        for(UUID id:new HashSet<>(eventGlowing)) clearGlow(id);
        saveCooldowns();
    }
    private void restoreCooldowns() {
        ConfigurationSection sec=getConfig().getConfigurationSection("reward-history");
        if(sec!=null) for(String s:sec.getKeys(false)) try { parkourRewards.put(UUID.fromString(s),sec.getLong(s)); } catch(Exception ignored) {}
    }
    private void saveCooldowns() {
        getConfig().set("reward-history",null);
        for(var e:parkourRewards.entrySet()) getConfig().set("reward-history."+e.getKey(),e.getValue());
        saveConfig();
    }
    private Location at(String path) {
        return new Location(world,getConfig().getDouble(path+".x"),getConfig().getDouble(path+".y"),getConfig().getDouble(path+".z"));
    }
    private void spawnNpc() {
        Location loc=at("npc");
        loc.setY(Math.max(world.getMinHeight()+1,loc.getY()));
        for(Entity e:world.getNearbyEntities(loc,2,3,2)) if(e instanceof Villager v && "§c§lБОСС НАГРАД".equals(v.getCustomName())) e.remove();
        npc=world.spawn(loc,Villager.class,v->{v.setCustomName("§c§lБОСС НАГРАД");v.setCustomNameVisible(true);v.setAI(false);v.setInvulnerable(true);v.setSilent(true);v.setRemoveWhenFarAway(false);});
    }
    private void announce(String msg) { Bukkit.broadcastMessage("§6[MEGA EVENTS] §f"+msg); }
    private void glow(Player p) { p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,120,0,false,false)); eventGlowing.add(p.getUniqueId()); }
    private void clearGlow(UUID id) { Player p=Bukkit.getPlayer(id); if(p!=null) p.removePotionEffect(PotionEffectType.GLOWING); eventGlowing.remove(id); }
    private boolean inside(Location l,EventArea a,int radius) { return a!=null && l.getWorld()==a.center.getWorld() && l.distanceSquared(a.center)<=radius*radius; }
    private int conf(String path,int fallback) { return getConfig().getInt(path,fallback); }

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(cmd.getName().equalsIgnoreCase("parkur")) {
            if(!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; }
            p.teleport(at("parkour.start")); p.sendMessage("§aПаркур! На вершине выбери награду."); return true;
        }
        if(!sender.hasPermission("megalevevents.admin")) { sender.sendMessage("§cНет прав."); return true; }
        if(args.length==0) { sender.sendMessage("§e/event start vant|fever|casino; /event stop vant|fever|casino; /event npc; /event parkourfinish"); return true; }
        if(args[0].equalsIgnoreCase("npc")) { if(npc!=null) npc.remove(); spawnNpc(); sender.sendMessage("NPC восстановлен."); return true; }
        if(args[0].equalsIgnoreCase("parkourfinish") && sender instanceof Player p) {
            Location l=p.getLocation(); getConfig().set("parkour.finish.x",l.getBlockX());getConfig().set("parkour.finish.y",l.getBlockY());getConfig().set("parkour.finish.z",l.getBlockZ());saveConfig();sender.sendMessage("§aФиниш установлен.");return true;
        }
        if(args.length<2) return false;
        String type=args[1].toLowerCase(Locale.ROOT);
        if(args[0].equalsIgnoreCase("stop")) { stop(type); sender.sendMessage("§eОстановлено: "+type); return true; }
        if(!args[0].equalsIgnoreCase("start")) return false;
        if(!Set.of("vant","fever","casino").contains(type)) return false;
        if(area(type)!=null) { sender.sendMessage("§cЭтот ивент уже идёт."); return true; }
        Location pos=randomPlace();
        if(pos==null) { sender.sendMessage("§cНе удалось найти безопасное место. Измени радиус RTP."); return true; }
        start(type,pos); return true;
    }
    private EventArea area(String type) { return switch(type) {case "vant"->vant;case "fever"->fever;case "casino"->casino;default->null;}; }
    private Location randomPlace() {
        int min=conf("random-location.min-radius",300),max=conf("random-location.max-radius",2500);
        for(int i=0;i<conf("random-location.attempts",40);i++) {
            double angle=random.nextDouble()*Math.PI*2, dist=min+random.nextDouble()*Math.max(1,max-min);
            int x=(int)(Math.cos(angle)*dist),z=(int)(Math.sin(angle)*dist);
            if(!world.isChunkLoaded(x>>4,z>>4)) world.getChunkAt(x>>4,z>>4).load();
            int y=world.getHighestBlockYAt(x,z);
            Block ground=world.getBlockAt(x,y-1,z);
            if(y>world.getSeaLevel() && y<world.getMaxHeight()-8 && ground.getType().isSolid() && !ground.isLiquid()) return new Location(world,x+.5,y,z+.5);
        }
        return null;
    }
    private void place(EventArea a,Location l,Material mat) {
        Block b=l.getBlock();
        if(!b.getType().isAir()) return;
        b.setType(mat);a.blocks.add(b.getLocation());
    }
    private void start(String type,Location pos) {
        EventArea a=new EventArea(type,pos,conf(type+".duration-seconds",900));
        if(type.equals("vant")) {
            vant=a; vantOpen=false; waves=0; keyHolder=null; keyOutsideSince=-1; keyDestroyedOnEscape=false;
            place(a,pos,Material.LODESTONE);
            Location chestLoc=pos.clone().add(3,0,3);
            place(a,chestLoc,Material.CHEST);
            if(chestLoc.getBlock().getState() instanceof Chest c) c.getBlockInventory().setItem(13,makeKey());
            announce("§dВАНТ §fпоявился: §e"+coords(pos)+" §f(15 минут). Найдите ключ в сундуке рядом!");
        } else if(type.equals("fever")) {
            fever=a; place(a,pos,Material.SHULKER_BOX);
            announce("§6ЗОЛОТАЯ ЛИХОРАДКА §fна координатах §e"+coords(pos));
        } else {
            casino=a; place(a,pos,Material.GOLD_BLOCK);
            announce("§eКАЗИНО §fпоявилось: §e"+coords(pos)+" §f(без PvP, ставка 1 млн)");
        }
    }
    private String coords(Location l) {return l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ();}
    private ItemStack makeKey() {
        ItemStack i=new ItemStack(Material.TRIPWIRE_HOOK);ItemMeta m=i.getItemMeta();m.setDisplayName("§d§lКЛЮЧ ВАНТА");
        m.getPersistentDataContainer().set(keyTag,PersistentDataType.STRING,UUID.randomUUID().toString());i.setItemMeta(m);return i;
    }
    private boolean isKey(ItemStack i) {return i!=null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(keyTag,PersistentDataType.STRING);}
    private boolean hasKey(Player p) {for(ItemStack i:p.getInventory().getContents()) if(isKey(i)) return true;return false;}
    private void removeKeys(Player p) {for(int i=0;i<p.getInventory().getSize();i++) if(isKey(p.getInventory().getItem(i))) p.getInventory().setItem(i,null);}
    private void restoreKey() {
        if(vant==null || vantOpen || keyDestroyedOnEscape) return;
        Block b=vant.center.clone().add(3,0,3).getBlock();
        if(b.getState() instanceof Chest c) {c.getBlockInventory().clear();c.getBlockInventory().setItem(13,makeKey());}
    }
    private void stopAll() {stop("vant");stop("fever");stop("casino");}
    private void stop(String type) {
        EventArea a=area(type); if(a==null)return;
        if(type.equals("vant")) {
            if(vantWaveTask!=null) {vantWaveTask.cancel();vantWaveTask=null;}
            for(Player p:Bukkit.getOnlinePlayers()) removeKeys(p);
            if(keyHolder!=null)clearGlow(keyHolder);
            keyHolder=null;vant=null;vantOpen=false;
        } else if(type.equals("fever"))fever=null;else casino=null;
        for(Location l:a.blocks) {
            Block b=l.getBlock();
            if(b.getType()==Material.LODESTONE || b.getType()==Material.CHEST || b.getType()==Material.SHULKER_BOX || b.getType()==Material.GOLD_BLOCK) b.setType(Material.AIR);
        }
        announce("§7Ивент "+type+" завершён.");
    }
    private void tick() {
        long now=System.currentTimeMillis();
        for(String type:List.of("vant","fever","casino")) {EventArea a=area(type);if(a!=null && now>=a.endsAt) stop(type);}
        if(vant!=null) {
            if(keyHolder!=null) {
                Player p=Bukkit.getPlayer(keyHolder);
                if(p==null || !p.isOnline() || !hasKey(p)) {if(p!=null)removeKeys(p);clearGlow(keyHolder);keyHolder=null;restoreKey();}
                else {
                    glow(p);
                    if(!inside(p.getLocation(),vant,conf("vant.territory-radius",20))) {
                        if(keyOutsideSince<0)keyOutsideSince=now;
                        if(now-keyOutsideSince>=conf("vant.outside-seconds",120)*1000L) {
                            Location l=p.getLocation().clone();removeKeys(p);clearGlow(p.getUniqueId());keyHolder=null;keyDestroyedOnEscape=true;
                            p.setHealth(0);announce("§cИГРОК "+p.getName()+" ПОПЫТАЛСЯ УКРАСТЬ КЛЮЧ! Координаты: "+coords(l)+". Скорее лутайте его ресурсы!");
                            // При побеге ключ исчезает, новый ключ не создаётся.
                        }
                    } else keyOutsideSince=-1;
                }
            }
            long age=(now-(vant.endsAt-conf("vant.duration-seconds",900)*1000L))/1000L;
            if(age>0 && age%conf("vant.invisibility-check-seconds",20)==0) {
                for(Player p:world.getPlayers()) if(inside(p.getLocation(),vant,20) && p.hasPotionEffect(PotionEffectType.INVISIBILITY))
                    p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,conf("vant.glowing-seconds",5)*20,0,false,false));
            }
        }
        for(Player p:Bukkit.getOnlinePlayers()) {
            for(ItemStack item:p.getInventory().getContents()) if(isBox(item)) {
                UUID id=boxId(item);if(id!=null && shulkerExpiry.getOrDefault(id,Long.MAX_VALUE)<=now) {
                    removeBox(p,id);boxInventories.remove(id);shulkerExpiry.remove(id);
                    p.sendMessage("§7Срок действия призового шалкера истёк.");
                }
            }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void onNpcDamage(EntityDamageByEntityEvent e) {
        if(npc==null || !e.getEntity().getUniqueId().equals(npc.getUniqueId()))return;
        e.setCancelled(true);
        if(!(e.getDamager() instanceof Player))return;
        npcHits++;
        if(npcHits>=conf("npc.hits-per-drop",100)) {
            npcHits=0;
            for(int i=0;i<conf("npc.drops",5);i++) world.dropItemNaturally(npc.getLocation(),loot());
            world.playSound(npc.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,1f,1f);
            announce("§aNPC на 0/0 выбросил 5 наград!");
        }
    }
    private ItemStack loot() {
        List<String> mats=getConfig().getStringList("loot.materials");
        if(mats.isEmpty())return new ItemStack(Material.TOTEM_OF_UNDYING);
        Material mat=Material.matchMaterial(mats.get(random.nextInt(mats.size())));
        if(mat==null)mat=Material.TOTEM_OF_UNDYING;
        ItemStack i=new ItemStack(mat);
        if(mat==Material.NETHERITE_SWORD || mat==Material.NETHERITE_AXE) i.addUnsafeEnchantment(Enchantment.SHARPNESS,5);
        if(mat==Material.NETHERITE_CHESTPLATE) i.addUnsafeEnchantment(Enchantment.PROTECTION,4);
        if(mat==Material.EXPERIENCE_BOTTLE || mat==Material.END_CRYSTAL)i.setAmount(16);
        return i;
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) {
        if(e.getClickedBlock()==null || e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Block b=e.getClickedBlock();Player p=e.getPlayer();
        if(vant!=null && b.getLocation().equals(vant.center.getBlock().getLocation())) {
            e.setCancelled(true);
            if(vantOpen){p.sendMessage("§eВант уже открыт!");return;}
            if(!p.getUniqueId().equals(keyHolder) || !hasKey(p)){p.sendMessage("§cНужен ключ из сундука рядом!");return;}
            removeKeys(p);clearGlow(p.getUniqueId());keyHolder=null;vantOpen=true;
            announce("§dВАНТ ОТКРЫТ игроком "+p.getName()+"! Вещи вылетают!");
            int interval=conf("vant.wave-seconds",5)*20;
            vantWaveTask=Bukkit.getScheduler().runTaskTimer(this,new Runnable(){int count=0;public void run(){
                if(vant==null || !vantOpen || count>=conf("vant.total-waves",24)){if(vantWaveTask!=null){vantWaveTask.cancel();vantWaveTask=null;}return;}
                count++;
                for(int i=0;i<conf("vant.drops-per-wave",3);i++) {
                    Item item=world.dropItem(vant.center.clone().add(0,1,0),loot());
                    item.setVelocity(new Vector(random.nextDouble()-.5,.4,random.nextDouble()-.5));
                }
                world.spawnParticle(Particle.FIREWORK,vant.center.clone().add(0,1,0),15,.5,.5,.5,.1);
            }},0L,interval);
            return;
        }
        if(fever!=null && b.getLocation().equals(fever.center.getBlock().getLocation())) {e.setCancelled(true);p.sendMessage("§eСломай шалкер, чтобы испытать удачу!");return;}
        if(casino!=null && b.getLocation().equals(casino.center.getBlock().getLocation())) {e.setCancelled(true);casinoMenu(p);return;}
    }
    @EventHandler public void onBreak(BlockBreakEvent e) {
        Block b=e.getBlock();Player p=e.getPlayer();
        if(fever!=null && b.getLocation().equals(fever.center.getBlock().getLocation())) {
            e.setCancelled(true);Location l=p.getLocation().clone();
            if(random.nextBoolean()) {p.getInventory().addItem(loot());p.sendMessage("§aЗолотая лихорадка: ты выиграл предмет!");}
            else {
                world.playSound(l,Sound.ENTITY_WARDEN_SONIC_BOOM,2,1);
                world.playSound(l,Sound.ENTITY_GUARDIAN_ATTACK,2,1);
                world.spawnParticle(Particle.SONIC_BOOM,l.clone().add(0,1,0),1);
                world.spawnParticle(Particle.END_ROD,l.clone().add(0,1,0),60,.4,1,.4,.04);
                p.setHealth(0);
                for(Player other:world.getPlayers()) if(other.getLocation().distanceSquared(l)<=400*400)
                    other.sendMessage("§cИГРОКА "+p.getName()+" УБИЛ ШАЛКЕР! §eКоординаты: "+coords(l)+" §aСКОРЕЕ ИДИ ЛУТАЙ ЕГО РЕСУРСЫ!");
                final int[] sec={0};BukkitTask[] task={null};
                task[0]=Bukkit.getScheduler().runTaskTimer(this,()->{
                    if(sec[0]>=conf("fever.death-signal-seconds",15)){task[0].cancel();return;}
                    world.strikeLightningEffect(l);
                    world.spawnParticle(Particle.END_ROD,l.clone().add(0,2,0),120,.1,8,.1,.002);
                    sec[0]+=2;
                },0L,40L);
            }
            stop("fever");return;
        }
        if((vant!=null && (b.getLocation().equals(vant.center.getBlock().getLocation()) || b.getLocation().equals(vant.center.clone().add(3,0,3).getBlock().getLocation()))) ||
            (casino!=null && b.getLocation().equals(casino.center.getBlock().getLocation())))e.setCancelled(true);
    }
    @EventHandler public void onChestClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(vant!=null && e.getView().getTopInventory().getHolder() instanceof Chest chest &&
            chest.getBlock().getLocation().equals(vant.center.clone().add(3,0,3).getBlock().getLocation())) {
            if(isKey(e.getCurrentItem()) && e.getClickedInventory()==e.getView().getTopInventory()) {
                e.setCancelled(true);
                if(keyHolder!=null){p.sendMessage("§cКлюч уже у другого игрока!");return;}
                if(p.getInventory().firstEmpty()<0){p.sendMessage("§cОсвободи место в инвентаре!");return;}
                ItemStack k=e.getCurrentItem().clone();e.getClickedInventory().setItem(e.getSlot(),null);
                p.getInventory().addItem(k);keyHolder=p.getUniqueId();keyOutsideSince=-1;glow(p);
                announce("§d"+p.getName()+" §fнашёл ключ от Ванта!");
            }
            return;
        }
        if(e.getView().getTitle().equals("§6Награда за паркур")) {
            e.setCancelled(true);
            if(e.getRawSlot()==11)giveParkourReward(p,false);
            if(e.getRawSlot()==15)giveParkourReward(p,true);
            return;
        }
        if(e.getView().getTitle().equals("§6Казино MegaLevSMP")) {
            e.setCancelled(true);
            if(e.getRawSlot()==13)spin(p);
            return;
        }
        if(e.getView().getTitle().equals("§dПризовой шалкер")) {
            if(e.getClickedInventory()==e.getView().getTopInventory() && (e.getClick().isShiftClick() || e.getClick().isKeyboardClick()))e.setCancelled(true);
            return;
        }
        if(isBox(e.getCurrentItem()) && e.getClickedInventory()==p.getInventory() && e.getClick().isRightClick()) {
            e.setCancelled(true);UUID id=boxId(e.getCurrentItem());if(id!=null)openBox(p,id);
        }
        if(isKey(e.getCurrentItem()) || isKey(e.getCursor()) || (e.getHotbarButton()>=0 && isKey(p.getInventory().getItem(e.getHotbarButton())))) {
            if(e.getClickedInventory()!=p.getInventory() || e.getClick().isShiftClick() || e.getClick().isKeyboardClick()) e.setCancelled(true);
        }
    }
    @EventHandler public void onDrop(PlayerDropItemEvent e) {if(isKey(e.getItemDrop().getItemStack()) || isBox(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity();
        if(keyHolder!=null && p.getUniqueId().equals(keyHolder)) {
            e.getDrops().removeIf(this::isKey);removeKeys(p);clearGlow(keyHolder);keyHolder=null;keyOutsideSince=-1;restoreKey();
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p=e.getPlayer();if(keyHolder!=null && keyHolder.equals(p.getUniqueId())){removeKeys(p);clearGlow(keyHolder);keyHolder=null;keyOutsideSince=-1;restoreKey();}
    }
    @EventHandler public void onPvp(EntityDamageByEntityEvent e) {
        if(!(e.getEntity() instanceof Player victim))return;
        Player attacker=null;
        if(e.getDamager() instanceof Player p)attacker=p;
        else if(e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p)attacker=p;
        if(attacker==null)return;
        if(inside(victim.getLocation(),casino,conf("casino.pvp-safe-radius",20)) || inside(attacker.getLocation(),casino,conf("casino.pvp-safe-radius",20)) ||
            inside(victim.getLocation(),new EventArea("parkour",at("parkour.start"),0),15))e.setCancelled(true);
    }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if(e.getTo()==null || e.getFrom().getBlock().equals(e.getTo().getBlock()))return;
        Player p=e.getPlayer();
        if(p.getWorld()!=world)return;
        if(e.getTo().distanceSquared(at("parkour.finish"))<=Math.pow(getConfig().getDouble("parkour.finish-radius",2),2) && !usedParkourFinishes.contains(p.getUniqueId())) {
            usedParkourFinishes.add(p.getUniqueId());parkourMenu(p);
        }
        if(e.getTo().distanceSquared(at("parkour.start"))<=9)usedParkourFinishes.remove(p.getUniqueId());
    }
    private ItemStack named(Material m,String name) {ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.setDisplayName(name);i.setItemMeta(meta);return i;}
    private void parkourMenu(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,"§6Награда за паркур");
        inv.setItem(11,named(Material.EMERALD,"§a1 000 000 $"));inv.setItem(15,named(Material.SHULKER_BOX,"§dПризовой шалкер"));p.openInventory(inv);
    }
    private void giveParkourReward(Player p,boolean box) {
        long now=System.currentTimeMillis();long cd=conf("parkour.reward-cooldown-hours",24)*3600000L;
        if(now-parkourRewards.getOrDefault(p.getUniqueId(),0L)<cd){p.sendMessage("§cНаграду можно получать раз в 24 часа.");p.closeInventory();return;}
        if(box) {
            if(p.getInventory().firstEmpty()<0){p.sendMessage("§cНужно место в инвентаре.");return;}
            UUID id=UUID.randomUUID();ItemStack item=named(Material.SHULKER_BOX,"§dПризовой шалкер (ПКМ в инвентаре)");
            ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(boxTag,PersistentDataType.STRING,id.toString());item.setItemMeta(meta);
            Inventory contents=Bukkit.createInventory(null,27,"§dПризовой шалкер");
            for(int i=0;i<9;i++)contents.setItem(i,loot());
            boxInventories.put(id,contents);shulkerExpiry.put(id,now+conf("parkour.shulker-expire-hours",24)*3600000L);
            p.getInventory().addItem(item);
        } else if(!money(p,conf("parkour.money-reward",1000000),false)){p.sendMessage("§cНе найдена экономика Vault. Награда не выдана.");return;}
        parkourRewards.put(p.getUniqueId(),now);saveCooldowns();p.closeInventory();p.sendMessage("§aНаграда за паркур получена!");
    }
    private boolean isBox(ItemStack i) {return i!=null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(boxTag,PersistentDataType.STRING);}
    private UUID boxId(ItemStack i) {try{return UUID.fromString(i.getItemMeta().getPersistentDataContainer().get(boxTag,PersistentDataType.STRING));}catch(Exception e){return null;}}
    private void openBox(Player p,UUID id) {
        Inventory inv=boxInventories.get(id);
        if(inv==null){p.sendMessage("§cЭтот шалкер больше недоступен.");return;}
        p.openInventory(inv);
    }
    private void removeBox(Player p,UUID id) {for(int i=0;i<p.getInventory().getSize();i++){ItemStack s=p.getInventory().getItem(i);if(isBox(s)&&id.equals(boxId(s)))p.getInventory().setItem(i,null);}}
    @EventHandler public void onClose(InventoryCloseEvent e) {
        if(!e.getView().getTitle().equals("§dПризовой шалкер"))return;
        UUID id=null;for(var entry:boxInventories.entrySet())if(entry.getValue()==e.getInventory()){id=entry.getKey();break;}
        if(id!=null && e.getInventory().isEmpty()) {removeBox((Player)e.getPlayer(),id);boxInventories.remove(id);shulkerExpiry.remove(id);}
    }
    @EventHandler public void onPlace(BlockPlaceEvent e) {if(isBox(e.getItemInHand()) || isKey(e.getItemInHand()))e.setCancelled(true);}
    private void casinoMenu(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,"§6Казино MegaLevSMP");
        inv.setItem(13,named(Material.GOLD_INGOT,"§6Крутить за 1 000 000 $"));p.openInventory(inv);
    }
    private void spin(Player p) {
        if(casino==null || !inside(p.getLocation(),casino,conf("casino.pvp-safe-radius",20))){p.closeInventory();return;}
        int price=conf("casino.spin-price",1000000);
        if(!money(p,price,true)){p.sendMessage("§cНедостаточно денег или нет экономики Vault.");return;}
        int n=random.nextInt(100),payout=n<50?0:n<70?price:n<85?2000000:n<95?5000000:10000000;
        if(payout>0)money(p,payout,false);
        p.sendMessage(payout==0?"§cКазино: проигрыш!":"§aКазино: выплата "+payout+" $!");p.closeInventory();
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private boolean money(Player p,double amount,boolean withdraw) {
        try {
            Class<?> eco=Class.forName("net.milkbowl.vault.economy.Economy");
            var reg=Bukkit.getServicesManager().getRegistration((Class)eco);
            if(reg==null)return false;
            Object provider=reg.getProvider();
            if(withdraw) {
                Method balance=eco.getMethod("getBalance",OfflinePlayer.class);
                if(((Number)balance.invoke(provider,p)).doubleValue()<amount)return false;
            }
            Method method=eco.getMethod(withdraw?"withdrawPlayer":"depositPlayer",OfflinePlayer.class,double.class);
            Object result=method.invoke(provider,p,amount);
            return (boolean)result.getClass().getMethod("transactionSuccess").invoke(result);
        }catch(Exception ex){getLogger().warning("Ошибка экономики: "+ex.getMessage());return false;}
    }
}
