package com.fusion.dev.cystol.host;

import com.fusion.dev.cystol.config.Lang;
import com.fusion.dev.cystol.config.PluginConfig;
import com.fusion.dev.cystol.event.EventManager;
import com.fusion.dev.cystol.event.EventPhase;
import com.fusion.dev.cystol.event.EventScheduler;
import com.fusion.dev.cystol.fx.EffectService;
import com.fusion.dev.cystol.util.GuiItems;
import com.fusion.dev.cystol.util.TextUtil;
import com.fusion.dev.cystol.util.TimeUtil;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Admin host console — fully wired to {@link EventManager} + {@link PluginConfig}.
 * Values always shown in item lore; clicks adjust or start chat time entry.
 */
public final class HostGui {

    public enum TimeField {
        START, FFA, END
    }

    private final EventManager eventManager;
    private final EventScheduler eventScheduler;
    private final PluginConfig config;
    private final Lang lang;
    private final EffectService effects;
    private final HostChatSession chatSession;

    public HostGui(
            EventManager eventManager,
            EventScheduler eventScheduler,
            PluginConfig config,
            Lang lang,
            EffectService effects,
            HostChatSession chatSession
    ) {
        this.eventManager = eventManager;
        this.eventScheduler = eventScheduler;
        this.config = config;
        this.lang = lang;
        this.effects = effects;
        this.chatSession = chatSession;
    }

    public void openMain(Player player) {
        Gui gui = Gui.gui()
                .title(TextUtil.component(lang.raw("host.gui.main.title"), Map.of()))
                .rows(6)
                .create();
        gui.setDefaultClickAction(e -> e.setCancelled(true));

        gui.setItem(1, 5, statusItem());

        boolean paused = eventManager.isPaused();
        gui.setItem(2, 5, navItem(
                paused ? Material.REDSTONE_TORCH : Material.TORCH,
                paused ? "&cPaused — click to &aUNPAUSE" : "&aRunning — click to &cPAUSE",
                List.of(
                        paused
                                ? "&7Schedule is frozen. Unpause to enter the live stage."
                                : "&7Event is live. Pause freezes progression.",
                        "&8Would-be stage: &f" + eventManager.livePhaseAt(Instant.now()).name(),
                        "",
                        "&eClick &7to toggle"
                ),
                e -> {
                    if (eventManager.isPaused()) {
                        EventPhase p = eventManager.unpause(Instant.now());
                        if (eventScheduler != null) {
                            eventScheduler.refreshDisplayNow();
                        }
                        player.sendMessage(lang.msg("admin.unpaused-ok", Map.of("phase", p.name())));
                    } else {
                        eventManager.pause();
                        if (eventScheduler != null) {
                            eventScheduler.refreshDisplayNow();
                        }
                        player.sendMessage(lang.msg("admin.paused-ok"));
                    }
                    clickFx(player);
                    openMain(player);
                }
        ));

        gui.setItem(3, 3, navItem(
                Material.CLOCK,
                lang.raw("host.gui.main.times.name"),
                List.of(
                        "&7Schedule start → finale → end",
                        "&7(timeline order).",
                        "&8Edits always pause until you unpause.",
                        "",
                        "&eClick to open"
                ),
                e -> {
                    clickFx(player);
                    openTimes(player);
                }
        ));
        gui.setItem(3, 5, navItem(
                Material.IRON_SWORD,
                lang.raw("host.gui.main.ffa.name"),
                List.of(
                        "&7Finale offset, announces, countdown",
                        "&8Before end: &f" + formatDuration(config.ffaBeforeEndSeconds()),
                        "",
                        "&eClick to open"
                ),
                e -> {
                    clickFx(player);
                    openFfa(player);
                }
        ));
        gui.setItem(3, 7, navItem(
                Material.GOLD_INGOT,
                lang.raw("host.gui.main.rewards.name"),
                List.of(
                        "&7Congrats message for top placers",
                        "&8Enabled: &f" + onOff(config.rewardsEnabled()),
                        "&8Max place: &f" + config.rewardsMaxPlace(),
                        "",
                        "&eClick to open"
                ),
                e -> {
                    clickFx(player);
                    openRewards(player);
                }
        ));

        boolean graceOn = config.graceEnabled() && config.graceSeconds() > 0L;
        gui.setItem(4, 5, navItem(
                graceOn ? Material.LIME_BANNER : Material.GRAY_BANNER,
                lang.raw("host.gui.main.grace.name"),
                List.of(
                        "&7Cosmetic last-N countdown before hunt",
                        "&8(title + sound + bossbar color/text only)",
                        "&8Enabled: &f" + onOff(config.graceEnabled()),
                        "&8Duration: &f" + formatDuration(config.graceSeconds()),
                        "",
                        "&eClick to open"
                ),
                e -> {
                    clickFx(player);
                    openGrace(player);
                }
        ));

        // Timeline order left → right: hunt → finale → end
        gui.setItem(5, 3, navItem(
                Material.LIME_DYE,
                "&aJump to hunt now",
                List.of("&7Sets start ≈ now (admin startnow).", "&eClick to run"),
                e -> {
                    clickFx(player);
                    player.closeInventory();
                    player.performCommand("preciv admin startnow");
                }
        ));
        gui.setItem(5, 5, navItem(
                Material.ORANGE_DYE,
                "&6Jump to finale now",
                List.of("&7Sets finale ≈ now (admin ffanow).", "&eClick to run"),
                e -> {
                    clickFx(player);
                    player.closeInventory();
                    player.performCommand("preciv admin ffanow");
                }
        ));
        gui.setItem(5, 7, navItem(
                Material.RED_DYE,
                "&cEnd event now",
                List.of("&7Sets end ≈ now (admin endnow).", "&eClick to run"),
                e -> {
                    clickFx(player);
                    player.closeInventory();
                    player.performCommand("preciv admin endnow");
                }
        ));

        boolean bar = config.tabBossbarEnabled();
        gui.setItem(6, 3, navItem(
                bar ? Material.DRAGON_HEAD : Material.WITHER_SKELETON_SKULL,
                bar ? "&aEvent bossbar: ON" : "&7Event bossbar: OFF",
                List.of(
                        "&8TAB bar for countdown + live event (all stages).",
                        "",
                        "&eClick &7to toggle"
                ),
                e -> {
                    config.setTabBossbarEnabled(!config.tabBossbarEnabled());
                    clickFx(player);
                    openMain(player);
                }
        ));
        gui.setItem(6, 5, navItem(
                Material.BARRIER,
                lang.raw("host.gui.common.close"),
                List.of("&7Close this menu"),
                e -> player.closeInventory()
        ));

        gui.open(player);
    }

    public void openTimes(Player player) {
        Gui gui = Gui.gui()
                .title(TextUtil.component(lang.raw("host.gui.times.title"), Map.of()))
                .rows(4)
                .create();
        gui.setDefaultClickAction(e -> e.setCancelled(true));

        // Left → right = timeline order: start → finale → end
        gui.setItem(2, 3, timeItem(
                Material.LIME_CONCRETE,
                "&aStart time",
                TimeField.START,
                eventManager.start().orElse(null),
                player
        ));
        Instant ffaOverride = eventManager.ffaOverride().orElse(null);
        Instant ffaEffective = eventManager.ffaMoment().orElse(null);
        List<String> ffaLore = new ArrayList<>();
        ffaLore.add("&8Override: &f" + (ffaOverride == null ? "— (use end − offset)" : TimeUtil.formatUtc(ffaOverride)));
        ffaLore.add("&8Effective finale: &f" + (ffaEffective == null ? "—" : TimeUtil.formatUtc(ffaEffective)));
        ffaLore.add("");
        ffaLore.add("&eLeft-click &7type a time in chat");
        ffaLore.add("&eRight-click &7clear override");
        ffaLore.add("&eShift+left &7set to now");
        gui.setItem(2, 5, navItem(Material.ORANGE_CONCRETE, "&6Finale time", ffaLore, e -> {
            handleTimeClick(player, TimeField.FFA, e.getClick());
        }));
        gui.setItem(2, 7, timeItem(
                Material.RED_CONCRETE,
                "&cEnd time",
                TimeField.END,
                eventManager.end().orElse(null),
                player
        ));

        gui.setItem(4, 5, backItem(player, this::openMain));
        gui.open(player);
    }

    public void openRewards(Player player) {
        Gui gui = Gui.gui()
                .title(TextUtil.component(lang.raw("host.gui.rewards.title"), Map.of()))
                .rows(4)
                .create();
        gui.setDefaultClickAction(e -> e.setCancelled(true));

        boolean on = config.rewardsEnabled();
        gui.setItem(2, 4, navItem(
                on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "&aRewards messages: ON" : "&7Rewards messages: OFF",
                List.of(
                        "&8Current: &f" + onOff(on),
                        "",
                        "&eClick &7to toggle"
                ),
                e -> {
                    config.setRewardsEnabled(!config.rewardsEnabled());
                    clickFx(player);
                    openRewards(player);
                }
        ));

        int maxPlace = config.rewardsMaxPlace();
        gui.setItem(2, 6, navItem(
                Material.GOLD_NUGGET,
                "&eMax place: &f" + maxPlace,
                List.of(
                        "&8Players finishing at this place or better",
                        "&8get the congrats chat (dense rank).",
                        "&80 = none",
                        "",
                        "&eLeft &7+1  &eRight &7−1",
                        "&eShift+left &7+5  &eShift+right &7−5"
                ),
                e -> {
                    int next = adjustInt(maxPlace, e.getClick(), 1, 5, 0, 15);
                    config.setRewardsMaxPlace(next);
                    clickFx(player);
                    openRewards(player);
                }
        ));

        gui.setItem(4, 5, backItem(player, this::openMain));
        gui.open(player);
    }

    /**
     * Pre-hunt cosmetic grace: enable/duration only — no schedule side effects.
     */
    public void openGrace(Player player) {
        Gui gui = Gui.gui()
                .title(TextUtil.component(lang.raw("host.gui.grace.title"), Map.of()))
                .rows(4)
                .create();
        gui.setDefaultClickAction(e -> e.setCancelled(true));

        boolean on = config.graceEnabled();
        gui.setItem(2, 3, navItem(
                on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "&aGrace cosmetics: ON" : "&7Grace cosmetics: OFF",
                List.of(
                        "&8Last-N countdown before hunt opens.",
                        "&8Bossbar color/text + one title/sound.",
                        "&8Does not change real stage or rules.",
                        "&8Current: &f" + onOff(on),
                        "",
                        "&eClick &7to toggle"
                ),
                e -> {
                    config.setGraceEnabled(!config.graceEnabled());
                    if (eventScheduler != null) {
                        eventScheduler.refreshDisplayNow();
                    }
                    clickFx(player);
                    openGrace(player);
                }
        ));

        gui.setItem(2, 5, longSetting(
                Material.CLOCK,
                "&eGrace duration",
                config.graceSeconds(),
                "Seconds before hunt start (cosmetic window). 0 = off.",
                60, 300, 0, 24 * 3600L,
                v -> {
                    config.setGraceSeconds(v);
                    if (eventScheduler != null) {
                        eventScheduler.refreshDisplayNow();
                    }
                },
                player,
                () -> openGrace(player)
        ));

        gui.setItem(2, 7, navItem(
                Material.BOOK,
                "&7What this does",
                List.of(
                        "&8When ON and duration > 0:",
                        "&8• Entering the window: title + sound",
                        "&8• Bossbar switches to grace color/text",
                        "&8• Scoreboard %phase% shows Grace",
                        "&8• Real phase stays COUNTDOWN until start"
                ),
                e -> {
                }
        ));

        gui.setItem(4, 5, backItem(player, this::openMain));
        gui.open(player);
    }

    public void openFfa(Player player) {
        Gui gui = Gui.gui()
                .title(TextUtil.component(lang.raw("host.gui.ffa.title"), Map.of()))
                .rows(6)
                .create();
        gui.setDefaultClickAction(e -> e.setCancelled(true));

        gui.setItem(2, 2, longSetting(
                Material.CLOCK,
                "&eBefore end (finale offset)",
                config.ffaBeforeEndSeconds(),
                "Seconds before end → finale TP (if no override).",
                60, 600, 0, 7 * 24 * 3600L,
                v -> {
                    config.setFfaBeforeEndSeconds(v);
                    // derived finale moment changes — no flag clear needed here
                },
                player,
                () -> openFfa(player)
        ));

        gui.setItem(2, 4, longSetting(
                Material.BELL,
                "&eAnnounce lead",
                config.announceLeadSeconds(),
                "How long before finale to start title announces.",
                60, 600, 0, 7 * 24 * 3600L,
                config::setAnnounceLeadSeconds,
                player,
                () -> openFfa(player)
        ));

        gui.setItem(2, 6, longSetting(
                Material.NOTE_BLOCK,
                "&eAnnounce interval",
                config.announceIntervalSeconds(),
                "Seconds between finale countdown titles.",
                30, 300, 1, 24 * 3600L,
                config::setAnnounceIntervalSeconds,
                player,
                () -> openFfa(player)
        ));

        gui.setItem(2, 8, longSetting(
                Material.OAK_SIGN,
                "&eOutside action bar",
                config.outsideActionbarSeconds(),
                "How long the “head to arena” bar lasts after TP.",
                5, 30, 1, 600L,
                config::setOutsideActionbarSeconds,
                player,
                () -> openFfa(player)
        ));

        boolean fcOn = config.ffaFinalCountdownEnabled();
        gui.setItem(4, 3, navItem(
                fcOn ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                fcOn ? "&aFinal countdown: ON" : "&7Final countdown: OFF",
                List.of(
                        "&85…1 titles just before ring TP.",
                        "&8Current: &f" + onOff(fcOn),
                        "",
                        "&eClick &7to toggle"
                ),
                e -> {
                    config.setFfaFinalCountdownEnabled(!config.ffaFinalCountdownEnabled());
                    clickFx(player);
                    openFfa(player);
                }
        ));

        int from = config.ffaFinalCountdownFromSeconds();
        gui.setItem(4, 5, navItem(
                Material.REPEATER,
                "&eCountdown from: &f" + from + "s",
                List.of(
                        "&8Highest digit (5 → 5,4,3,2,1).",
                        "&80 = off even if toggle is on.",
                        "",
                        "&eLeft &7+1  &eRight &7−1"
                ),
                e -> {
                    int next = adjustInt(from, e.getClick(), 1, 1, 0, 15);
                    config.setFfaFinalCountdownFromSeconds(next);
                    clickFx(player);
                    openFfa(player);
                }
        ));

        String audience = config.ffaFinalCountdownAudience();
        boolean all = !"eligible".equals(audience);
        gui.setItem(4, 7, navItem(
                all ? Material.PLAYER_HEAD : Material.SKELETON_SKULL,
                all ? "&eAudience: &feveryone" : "&eAudience: &fTP-eligible only",
                List.of(
                        "&8Who sees 5…1 titles/sounds.",
                        "&8Eligible = survival, not vanished, no skip.",
                        "",
                        "&eClick &7to toggle"
                ),
                e -> {
                    config.setFfaFinalCountdownAudience(all ? "eligible" : "all");
                    clickFx(player);
                    openFfa(player);
                }
        ));

        double spacing = config.minPlayerSpacing();
        gui.setItem(5, 5, navItem(
                Material.COMPASS,
                "&eRing spacing: &f" + formatDouble(spacing),
                List.of(
                        "&8Target blocks between neighbors on the ring.",
                        "&8Few players → tight; many → up to diameter cap.",
                        "",
                        "&eLeft &7+0.5  &eRight &7−0.5",
                        "&eShift &7±2"
                ),
                e -> {
                    double step = e.getClick().isShiftClick() ? 2.0 : 0.5;
                    double next = spacing;
                    if (e.getClick().isLeftClick()) {
                        next += step;
                    } else if (e.getClick().isRightClick()) {
                        next -= step;
                    }
                    next = Math.max(1.0, Math.min(64.0, next));
                    config.setMinPlayerSpacing(next);
                    clickFx(player);
                    openFfa(player);
                }
        ));

        gui.setItem(6, 5, backItem(player, this::openMain));
        gui.open(player);
    }

    // --- builders ----------------------------------------------------------------

    private GuiItem statusItem() {
        EventPhase phase = eventManager.phase();
        List<String> lore = new ArrayList<>();
        lore.add("&8Stage: &f" + phase.name());
        lore.add("&8Start: &f" + eventManager.start().map(TimeUtil::formatUtc).orElse("—"));
        lore.add("&8Finale: &f" + eventManager.ffaMoment().map(TimeUtil::formatUtc).orElse("—")
                + (eventManager.ffaOverride().isPresent() ? " &8(override)" : " &8(derived)"));
        lore.add("&8End: &f" + eventManager.end().map(TimeUtil::formatUtc).orElse("—"));
        lore.add("");
        lore.add("&7Open a section below to edit.");
        return navItem(Material.BOOK, lang.raw("host.gui.main.status.name"), lore, e -> {
        });
    }

    private GuiItem timeItem(Material mat, String name, TimeField field, Instant value, Player player) {
        List<String> lore = new ArrayList<>();
        lore.add("&8Current: &f" + (value == null ? "—" : TimeUtil.formatUtc(value)));
        lore.add("");
        lore.add("&eLeft-click &7type in chat:");
        lore.add("  &fyyyy/MM/dd HH:mm:ss &7(UTC absolute)");
        lore.add("  &f-r 1d2h5m8s &7(from now; omit units = 0)");
        lore.add("&eShift+left &7set to now");
        if (field == TimeField.FFA) {
            lore.add("&eRight-click &7clear override");
        }
        lore.add("&8Any edit pauses the event.");
        return navItem(mat, name, lore, e -> handleTimeClick(player, field, e.getClick()));
    }

    private void handleTimeClick(Player player, TimeField field, ClickType click) {
        if (click == ClickType.SHIFT_LEFT) {
            Instant now = Instant.now();
            applyTime(field, now);
            player.sendMessage(lang.msg("host.gui.times.set-ok", Map.of(
                    "field", fieldLabel(field),
                    "time", TimeUtil.formatUtc(now)
            )));
            clickFx(player);
            openTimes(player);
            return;
        }
        if (click.isRightClick()) {
            if (field == TimeField.FFA) {
                eventManager.setFfaOverride(null);
                player.sendMessage(lang.msg("host.gui.times.cleared", Map.of("field", fieldLabel(field))));
                clickFx(player);
                openTimes(player);
                return;
            }
            if (field == TimeField.START) {
                // clear start only via full clear is harsh — require chat cancel
                player.sendMessage(lang.msg("host.gui.times.use-chat-clear"));
                return;
            }
            if (field == TimeField.END) {
                player.sendMessage(lang.msg("host.gui.times.use-chat-clear"));
                return;
            }
        }
        if (click.isLeftClick()) {
            player.closeInventory();
            chatSession.begin(player, field);
            player.sendMessage(lang.msg("host.gui.times.prompt", Map.of(
                    "field", fieldLabel(field),
                    "format", "yyyy/MM/dd HH:mm:ss  or  -r 1d2h"
            )));
            clickFx(player);
        }
    }

    void applyTime(TimeField field, Instant instant) {
        switch (field) {
            case START -> eventManager.setStart(instant);
            case FFA -> eventManager.setFfaOverride(instant);
            case END -> eventManager.setEnd(instant);
        }
    }

    void clearTime(TimeField field) {
        if (field == TimeField.FFA) {
            eventManager.setFfaOverride(null);
        }
    }

    private GuiItem longSetting(
            Material mat,
            String name,
            long value,
            String help,
            long step,
            long bigStep,
            long min,
            long max,
            Consumer<Long> writer,
            Player player,
            Runnable reopen
    ) {
        List<String> lore = new ArrayList<>();
        lore.add("&8Current: &f" + value + "s &8(" + formatDuration(value) + ")");
        lore.add("&7" + help);
        lore.add("");
        lore.add("&eLeft &7+" + step + "  &eRight &7−" + step);
        lore.add("&eShift+left &7+" + bigStep + "  &eShift+right &7−" + bigStep);
        return navItem(mat, name, lore, e -> {
            long next = adjustLong(value, e.getClick(), step, bigStep, min, max);
            writer.accept(next);
            clickFx(player);
            reopen.run();
        });
    }

    private GuiItem backItem(Player player, Consumer<Player> back) {
        return navItem(
                Material.ARROW,
                lang.raw("host.gui.common.back"),
                List.of("&7Back"),
                e -> {
                    clickFx(player);
                    back.accept(player);
                }
        );
    }

    private GuiItem navItem(Material mat, String nameAmp, List<String> loreAmp, Consumer<InventoryClickEvent> action) {
        Component name = TextUtil.component(nameAmp == null ? " " : nameAmp, Map.of());
        List<Component> lore = TextUtil.componentList(loreAmp == null ? List.of() : loreAmp, Map.of());
        return GuiItems.item(mat, name, lore, e -> {
            e.setCancelled(true);
            if (action != null) {
                action.accept(e);
            }
        });
    }

    private void clickFx(Player player) {
        effects.play(player, EffectService.EffectKey.MENU_SELECT_TARGET);
    }

    static int adjustInt(int value, ClickType click, int step, int bigStep, int min, int max) {
        int s = click.isShiftClick() ? bigStep : step;
        if (click.isLeftClick()) {
            value += s;
        } else if (click.isRightClick()) {
            value -= s;
        }
        return Math.max(min, Math.min(max, value));
    }

    static long adjustLong(long value, ClickType click, long step, long bigStep, long min, long max) {
        long s = click.isShiftClick() ? bigStep : step;
        if (click.isLeftClick()) {
            value += s;
        } else if (click.isRightClick()) {
            value -= s;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String onOff(boolean v) {
        return v ? "ON" : "OFF";
    }

    private static String fieldLabel(TimeField f) {
        return switch (f) {
            case START -> "start";
            case FFA -> "finale";
            case END -> "end";
        };
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    private static String formatDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
