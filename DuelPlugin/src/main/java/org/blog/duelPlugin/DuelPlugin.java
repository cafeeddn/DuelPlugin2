package org.blog.duelPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class DuelPlugin extends JavaPlugin implements Listener {

    // === 메뉴 타이틀 ===
    private static final String MENU_TITLE_SELECT_TARGET = "§a듀얼 상대 선택";
    private static final String MENU_TITLE_CLASS = "§a병과 선택";

    // 병과 슬롯(27칸 인벤 중앙)
    private static final int SLOT_SWORD = 11;
    private static final int SLOT_BOW   = 13;
    private static final int SLOT_LANCE = 15;

    // 말/상태 추적
    private final Set<UUID> duelHorseIds = new HashSet<>();
    private final Set<UUID> sneakingPlayers = new HashSet<>();
    private final Map<UUID, UUID> pendingTarget = new HashMap<>(); // opener -> target

    // 진행 중 듀얼: 플레이어 UUID -> Duel
    private final Map<UUID, Duel> activeDuels = new HashMap<>();
    // 사망자: 리스폰 시 처리
    private final Set<UUID> pendingRespawn = new HashSet<>();

    private enum CombatClass { SWORD, BOW, LANCE }

    private static final class Duel {
        final UUID p1, p2;
        final CombatClass picked;          // 현재 동일 병과
        final Set<UUID> horses = new HashSet<>(); // 이 듀얼에서 소환된 말들
        int actionbarTaskId = -1;          // 검 병과용 액션바 태스크

        Duel(UUID p1, UUID p2, CombatClass picked) {
            this.p1 = p1; this.p2 = p2; this.picked = picked;
        }
        boolean involves(UUID u) { return p1.equals(u) || p2.equals(u); }
        UUID other(UUID u) { return p1.equals(u) ? p2 : p1; }
    }

    // 서버 스폰 좌표 고정
    private static final Location SERVER_SPAWN = new Location(Bukkit.getWorlds().get(0), 2, -60, -8);

   @Override
public void onEnable() {
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getPluginManager().registerEvents(new HorseHeal(this), this);
    getLogger().info("DuelPlugin Enabled!");
}

// 다른 클래스에서 activeDuels에 접근 가능하게 getter 추가
public Map<UUID, Duel> getActiveDuels() {
    return activeDuels;
}

    /* =========================
           입력 감지 (Shift+F)
       ========================= */

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking()) sneakingPlayers.add(player.getUniqueId());
        else sneakingPlayers.remove(player.getUniqueId());
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (sneakingPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            // 듀얼 중엔 메뉴 못 열게 막기
            if (activeDuels.containsKey(player.getUniqueId())) {
                player.sendMessage("§c듀얼 진행 중에는 메뉴를 열 수 없습니다.");
                return;
            }
            openTargetMenu(player);
        }
    }

    /* =========================
               메뉴들
       ========================= */

    private void openTargetMenu(Player opener) {
        Inventory menu = Bukkit.createInventory(null, 54, MENU_TITLE_SELECT_TARGET);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(opener)) continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(p);
            meta.setDisplayName("§a" + p.getName());
            head.setItemMeta(meta);

            menu.addItem(head);
        }
        opener.openInventory(menu);
    }

    private void openClassMenu(Player opener, Player target) {
        pendingTarget.put(opener.getUniqueId(), target.getUniqueId());

        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CLASS);

        inv.setItem(SLOT_SWORD, icon(Material.DIAMOND_SWORD, "§a검",
                "§7근접전 특화 (데미지 +10%)", "§e클릭하여 선택"));
        inv.setItem(SLOT_BOW, icon(Material.BOW, "§a활",
                "§7원거리 특화 (무한 화살)", "§e클릭하여 선택"));

        ItemStack lance = new ItemStack(Material.IRON_AXE);
        ItemMeta lm = lance.getItemMeta();
        lm.setDisplayName("§a창");
        lm.setLore(Arrays.asList("§7리소스팩: 도끼→창", "§e클릭하여 선택"));
        lance.setItemMeta(lm);
        // 창: 가착 + 날카3
        lance.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
        lance.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
        inv.setItem(SLOT_LANCE, lance);

        opener.openInventory(inv);
    }

    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null && lore.length > 0) m.setLore(Arrays.asList(lore));
        it.setItemMeta(m);
        return it;
    }

    /* =========================
            인벤 클릭 처리
       ========================= */

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        if (title == null) return;

        if (title.equals(MENU_TITLE_SELECT_TARGET) || title.equals(MENU_TITLE_CLASS)) {
            event.setCancelled(true);
        } else return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        if (title.equals(MENU_TITLE_SELECT_TARGET)) {
            String targetName = clicked.getItemMeta().getDisplayName().replace("§a", "");
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null || !target.isOnline()) {
                player.sendMessage("§c플레이어를 찾을 수 없습니다.");
                return;
            }
            if (activeDuels.containsKey(player.getUniqueId()) || activeDuels.containsKey(target.getUniqueId())) {
                player.sendMessage("§c상대 또는 당신이 이미 듀얼 중입니다.");
                return;
            }
            openClassMenu(player, target);
            return;
        }

        if (title.equals(MENU_TITLE_CLASS)) {
            UUID targetId = pendingTarget.remove(player.getUniqueId());
            if (targetId == null) {
                player.sendMessage("§c상대 정보를 찾을 수 없습니다.");
                player.closeInventory();
                return;
            }
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                player.sendMessage("§c상대가 오프라인입니다.");
                player.closeInventory();
                return;
            }

            int slot = event.getRawSlot();
            CombatClass picked = switch (slot) {
                case SLOT_SWORD -> CombatClass.SWORD;
                case SLOT_BOW   -> CombatClass.BOW;
                case SLOT_LANCE -> CombatClass.LANCE;
                default -> null;
            };
            if (picked == null) return;

            player.closeInventory();
            startDuel(player, target, picked);
        }
    }

    /* =========================
              듀얼 로직
       ========================= */

    private void startDuel(Player p1, Player p2, CombatClass picked) {
        // 1. 위치 이동 (듀얼 경기장 좌표 예시)
        p1.teleport(new Location(p1.getWorld(), 132, -56, 57));
        p2.teleport(new Location(p2.getWorld(), 132, -54, 151));

        // 2. 모험 모드
        p1.setGameMode(GameMode.ADVENTURE);
        p2.setGameMode(GameMode.ADVENTURE);

        // 3. 병과 조건에 따라 말 스폰: 검/활만 말, 창은 말 없음
        Horse h1 = null, h2 = null;
        if (picked == CombatClass.SWORD || picked == CombatClass.BOW) {
            h1 = spawnDuelHorse(p1);
            h2 = spawnDuelHorse(p2);
        }

        // 4. 장비 지급
        setupGearCommon(p1);
        setupGearCommon(p2);
        giveClassLoadout(p1, picked);
        giveClassLoadout(p2, picked);

        // 5. 탑승
        if (h1 != null) h1.addPassenger(p1);
        if (h2 != null) h2.addPassenger(p2);

        // 6. 듀얼 등록
        Duel duel = new Duel(p1.getUniqueId(), p2.getUniqueId(), picked);
        if (h1 != null) { duel.horses.add(h1.getUniqueId()); duelHorseIds.add(h1.getUniqueId()); }
        if (h2 != null) { duel.horses.add(h2.getUniqueId()); duelHorseIds.add(h2.getUniqueId()); }
        activeDuels.put(p1.getUniqueId(), duel);
        activeDuels.put(p2.getUniqueId(), duel);

        // 7. 메시지
        String cls = switch (picked) { case SWORD -> "검"; case BOW -> "활"; case LANCE -> "창"; };
        p1.sendMessage("§a" + p2.getName() + "와 듀얼이 시작되었습니다! §7(병과: " + cls + ")");
        p2.sendMessage("§a" + p1.getName() + "와 듀얼이 시작되었습니다! §7(병과: " + cls + ")");

        // 8. 검 병과: 액션바 주기 송출
        if (picked == CombatClass.SWORD) {
            int taskId = getServer().getScheduler().runTaskTimer(this, () -> {
                Component c = Component.text("검기마 데미지 10% 증가").color(NamedTextColor.AQUA);
                Player a = Bukkit.getPlayer(duel.p1);
                Player b = Bukkit.getPlayer(duel.p2);
                if (a != null && a.isOnline()) a.sendActionBar(c);
                if (b != null && b.isOnline()) b.sendActionBar(c);
            }, 0L, 40L).getTaskId(); // 2초마다
            duel.actionbarTaskId = taskId;
        }
    }

    // 공통 방어구
    private void setupGearCommon(Player p) {
        p.getInventory().clear();
        p.getInventory().setHelmet(ench(new ItemStack(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setChestplate(ench(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setLeggings(ench(new ItemStack(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setBoots(ench(new ItemStack(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
    }

    // 병과별 지급
    private void giveClassLoadout(Player p, CombatClass picked) {
        p.getInventory().setItem(8, new ItemStack(Material.GOLDEN_APPLE, 8));
        p.getInventory().setItem(7, new ItemStack(Material.COOKED_BEEF, 32));

        switch (picked) {
            case SWORD -> {
                ItemStack sword = ench(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2);
                p.getInventory().setItem(0, sword);
            }
            case BOW -> {
                ItemStack bow = ench(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 2);
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
            }
            case LANCE -> {
                ItemStack axe = new ItemStack(Material.IRON_AXE);
                ItemMeta m = axe.getItemMeta();
                m.setDisplayName("§a창");
                axe.setItemMeta(m);
                axe.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
                axe.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
                p.getInventory().setItem(0, axe);
            }
        }
    }

    private Horse spawnDuelHorse(Player player) {
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setOwner(player);
        horse.setTamed(true);
        horse.setAdult();
        horse.setMaxHealth(30.0);
        horse.setHealth(30.0);

        AttributeInstance speed = horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.30);
        horse.setJumpStrength(1.0);

        ItemStack armor = new ItemStack(Material.DIAMOND_HORSE_ARMOR);
        ItemMeta meta = armor.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 1, true);
        armor.setItemMeta(meta);

        horse.getInventory().setArmor(armor);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        return horse;
    }

    /* =========================
          전투/종료 처리
       ========================= */

    // 검 병과 데미지 +10%
    @EventHandler(ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        Duel duel = activeDuels.get(attacker.getUniqueId());
        if (duel == null) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!duel.involves(victim.getUniqueId())) return;
        if (duel.picked == CombatClass.SWORD) {
            e.setDamage(e.getDamage() * 1.10);
        }
    }

    // 플레이어 사망 → 듀얼 종료
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Duel duel = activeDuels.get(dead.getUniqueId());
        if (duel == null) return;

        e.getDrops().clear();
        e.setDroppedExp(0);

        pendingRespawn.add(dead.getUniqueId());
        endDuel(duel);
    }

    // 리스폰 시 패자 처리
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!pendingRespawn.remove(p.getUniqueId())) return;
        e.setRespawnLocation(SERVER_SPAWN);
        Bukkit.getScheduler().runTask(this, () -> {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
        });
    }

    // 말 사망 → 드랍 방지
    @EventHandler
    public void onHorseDeath(EntityDeathEvent e) {
        if (e.getEntity().getType() == EntityType.HORSE) {
            UUID id = e.getEntity().getUniqueId();
            if (duelHorseIds.remove(id)) {
                e.getDrops().clear();
                e.setDroppedExp(0);
            }
        }
    }

    // 듀얼 종료 처리
    private void endDuel(Duel duel) {
        if (duel.actionbarTaskId != -1) {
            getServer().getScheduler().cancelTask(duel.actionbarTaskId);
        }

        for (UUID hid : duel.horses) {
            for (World w : Bukkit.getWorlds()) {
                var ent = w.getEntity(hid);
                if (ent instanceof Horse h && !h.isDead()) h.remove();
            }
            duelHorseIds.remove(hid);
        }

        Player p1 = Bukkit.getPlayer(duel.p1);
        Player p2 = Bukkit.getPlayer(duel.p2);
        Component msg = Component.text("듀얼이 종료되었습니다.").color(NamedTextColor.GRAY);

        if (p1 != null && p1.isOnline()) {
            p1.getInventory().clear(); p1.getInventory().setArmorContents(null);
            p1.teleport(SERVER_SPAWN);
            p1.sendMessage(msg);
        }
        if (p2 != null && p2.isOnline()) {
            p2.getInventory().clear(); p2.getInventory().setArmorContents(null);
            p2.teleport(SERVER_SPAWN);
            p2.sendMessage(msg);
        }

        activeDuels.remove(duel.p1);
        activeDuels.remove(duel.p2);
    }

    private ItemStack ench(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
        return item;
    }
}
