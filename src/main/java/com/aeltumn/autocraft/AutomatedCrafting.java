package com.aeltumn.autocraft;

import io.papermc.paper.dialog.Dialog;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import com.aeltumn.autocraft.helpers.DropperOutput;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.aeltumn.autocraft.api.CrafterRegistry;
import com.aeltumn.autocraft.impl.CrafterRegistryImpl;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class AutomatedCrafting extends JavaPlugin implements MachineScreen.Source {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static AutomatedCrafting INSTANCE;

    private CrafterRegistry registry;
    public MachineSkin skin;
    private MachineScreen screen;
    private RecipeLoader recipeLoader;

    /**
     * Returns the instance of this plugin, will be null if the plugin hasn't been loaded
     * yet.
     */
    @Deprecated
    public static AutomatedCrafting getInstance() {
        return INSTANCE;
    }

    @Override
    public void onEnable() {
        INSTANCE = this;

        saveDefaultConfig();
        reloadConfig();

        recipeLoader = new RecipeLoader(this); //The recipe loader keeps track of all the recipes the autocrafters support.
        registry = new CrafterRegistryImpl(this); //The registry tracks all autocrafters and ticks them to craft every second.
        CrafterRegistryImpl impl = (CrafterRegistryImpl) registry;
        skin = new MachineSkin(this, b -> impl.ready(b) ? "crafter" : null, false).carry(impl::carry, impl::lift, impl::place);
        Bukkit.getPluginManager().registerEvents(new CreationListener(), this);
        new MachinePanel(this, b -> registry.isAutocrafter(b));
        screen = new MachineScreen(this, this);
    }

    @Override
    public void onDisable() {
        // Save the crafter registry if it's dirty
        if (registry.isDirty()) {
            registry.forceSave();
        }

        registry.shutdown();
        INSTANCE = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("reloadrecipes"))
            getRecipeLoader().reload(sender);
        return false;
    }


    public CrafterRegistry getCrafterRegistry() {
        return registry;
    }

    public RecipeLoader getRecipeLoader() {
        return recipeLoader;
    }

    public void info(String text) {
        getLogger().info(text);
    }

    public void warning(String text) {
        getLogger().warning(text);
    }

    public boolean machineShell(Block block) {
        return registry != null && registry.isAutocrafter(block);
    }

    public Map.Entry<String, Dialog> machinePanel(Player player, Block block) {
        return screen == null ? null : screen.entry(player, block);
    }

    public void machineOpened(Player player, Dialog dialog) {
        if (screen != null) screen.opened(player, dialog);
    }

    @Override
    public boolean allowed(Player player, Block block) {
        return registry != null && registry.isAutocrafter(block) && MachinePanel.canEdit(player, block, null);
    }

    @Override
    public MachineView.Machine view(Block block) {
        ItemStack item = ((CrafterRegistryImpl) registry).item(block);
        if (item == null || !(block.getState(false) instanceof Container)) return null;
        String look = skin == null ? null : skin.skinOf(block);
        MachineView.Status status = MachineView.status(look, true);
        boolean running = status == MachineView.Status.RUNNING;
        Block front = block.getBlockData() instanceof Directional d ? block.getRelative(d.getFacing()) : null;
        Inventory into = front == null ? null : DropperOutput.containerAt(front);
        boolean composter = front != null && front.getType() == Material.COMPOSTER;
        MachineView.Flow craft = new MachineView.Flow("合成", running, "target_crafting_table", MachineView.name(item), null,
                MachineView.hover("展示框裡的物品就是要合成的成品", MachineView.item("合成", item)));
        MachineView.Flow out = new MachineView.Flow("送出", running, into != null ? "target_chest" : null,
                into != null ? "容器" : composter ? "堆肥桶" : "掉在前方", into == null ? null : MachineView.count(into, s -> s.getType() == item.getType()),
                MachineView.hover(into != null ? "前方容器裡的成品" : composter ? "成品直接倒進前方的堆肥桶" : "前方沒有容器，成品會掉在地上", null));
        boolean pulse = ConfigFile.craftOnRedstonePulse();
        return new MachineView.Machine("輝煌合成器", "生產", look == null ? "crafter" : look, "合成器", null, null,
                "在投擲器貼上展示框並放進成品，材料齊了就自動合成。", "合成方式", pulse ? "紅石" : "自動", false, List.of(craft, out), List.of(),
                status, MachineView.hint(status, "正在合成展示框裡的成品", "前方放不下合成出來的物品", "裡面的材料不夠合成",
                        pulse ? "等待紅石訊號" : "等待材料"));
    }

    @Override
    public void act(Player player, Block block, String action, int index, String text) {
    }

    @Override
    public void unset(Player player, Block block) {
        if (registry == null || !registry.isAutocrafter(block)) return;
        registry.destroy(block.getLocation());
        if (block.getState() instanceof org.bukkit.Nameable named) {
            named.setCustomName(null);
            ((org.bukkit.block.BlockState) named).update();
        }
        player.sendActionBar("§7｜§6系統§7｜§f飯娘：§c已移除自動合成器設定。");
    }
}
