package com.liuxingyu.liutrade;

import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.NormalWindow;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 配方查看界面（Sparrow UI 箱子窗口）：右键交易打开，ESC 或「返回」按钮回到上一界面。
 *
 * <p>支持合成（有序/无序）、染色（transmute）、烧炼、切石、锻造等配方；同一物品有多个配方时
 * 用左右箭头切换。作为交易界面的下一扇打开时，ESC 由 Sparrow 会话原生返回；从原生村民界面
 * 打开时是根窗，结束后由 {@code onEnd} 重新打开村民界面。</p>
 */
final class RecipeGui {

    /** 3×3 工作格在 54 格箱子里的位置。 */
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int SLOT_ARROW = 24;
    static final int SLOT_RESULT = 25;
    static final int SLOT_PREV = 45;
    static final int SLOT_BACK = 49;
    static final int SLOT_NEXT = 53;

    private final LiuTrade plugin;
    private final Player player;
    private final List<Recipe> recipes;
    private final MutableSignal<Integer> index;
    private final NormalWindow window;

    private RecipeGui(LiuTrade plugin, Player player, ItemStack target, List<Recipe> recipes, Runnable onEnd) {
        this.plugin = plugin;
        this.player = player;
        this.recipes = recipes;
        this.index = Signal.of(0);
        this.window = NormalWindow.builder()
                .setTitleSupplier(() -> Msg.component("&8配方：&f" + plugin.itemName(target)))
                .setUpperPane(buildPane())
                .setBackOnPlayerClose(true)
                .addSessionEndHandler(reason -> {
                    if (onEnd != null && player.isOnline()) {
                        onEnd.run();
                    }
                })
                .build(player);
    }

    /** 构造一扇作为交易界面下一级的配方窗（ESC 由 Sparrow 会话原生返回）。 */
    static RecipeGui child(LiuTrade plugin, Player player, ItemStack target, List<Recipe> recipes) {
        return new RecipeGui(plugin, player, target, recipes, null);
    }

    /** 作为根窗打开（原生村民界面用），关闭后回调 {@code onEnd}。 */
    static void openRoot(LiuTrade plugin, Player player, ItemStack target, List<Recipe> recipes, Runnable onEnd) {
        new RecipeGui(plugin, player, target, recipes, onEnd).window.open();
    }

    NormalWindow window() {
        return window;
    }

    private NormalPane buildPane() {
        NormalPane pane = Pane.empty(9, 6);
        pane.setBackgroundItem(border());
        Item grid = gridItem();
        for (int slot : GRID) {
            pane.setElement(slot, Element.item(grid));
        }
        pane.setElement(SLOT_ARROW, Element.item(Item.simple(staticItem(Material.ARROW, "&7→", null))));
        pane.setElement(SLOT_RESULT, Element.item(resultItem()));
        pane.setElement(SLOT_BACK, Element.item(backItem()));
        pane.setElement(SLOT_PREV, Element.item(switchItem(false)));
        pane.setElement(SLOT_NEXT, Element.item(switchItem(true)));
        return pane;
    }

    private Item gridItem() {
        return Item.builder()
                .setItemProvider(context -> {
                    ItemStack ingredient = ingredient(context.windowSlot);
                    return ingredient == null || ingredient.getType().isAir() ? border() : ingredient.clone();
                })
                .dependsOn(index)
                .build();
    }

    private Item resultItem() {
        return Item.builder()
                .setItemProvider(context -> resultIcon(current()))
                .dependsOn(index)
                .build();
    }

    private Item backItem() {
        return Item.builder()
                .setItemProviderConstant(staticItem(Material.BARRIER, "&c返回 &7(ESC)", List.of("&7回到交易界面")))
                .addClickHandler(click -> click.window().backOrClose())
                .build();
    }

    private Item switchItem(boolean next) {
        return Item.builder()
                .setItemProvider(context -> {
                    if (recipes.size() <= 1) {
                        return border();
                    }
                    return staticItem(Material.SPECTRAL_ARROW, next ? "&e下一个配方" : "&e上一个配方",
                            List.of("&7第 " + (index.get() + 1) + " / " + recipes.size() + " 个"));
                })
                .dependsOn(index)
                .addClickHandler(click -> {
                    if (recipes.size() > 1) {
                        index.update(value -> next
                                ? (value + 1) % recipes.size()
                                : (value - 1 + recipes.size()) % recipes.size());
                    }
                })
                .build();
    }

    private Recipe current() {
        return recipes.get(index.get());
    }

    // ---------------- 配方解析 ----------------

    /** 取一笔物品的合成配方（合成优先，其次烧炼/切石/锻造）；失败返回空列表。 */
    static List<Recipe> find(ItemStack target) {
        if (target == null || target.getType().isAir()) {
            return List.of();
        }
        try {
            List<Recipe> all = new ArrayList<>(Bukkit.getRecipesFor(target));
            all.sort(Comparator.comparingInt(RecipeGui::priority));
            return all;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static int priority(Recipe recipe) {
        if (recipe instanceof ShapedRecipe) {
            return 0;
        }
        if (recipe instanceof ShapelessRecipe) {
            return 1;
        }
        if (recipe instanceof TransmuteRecipe) {
            return 2;
        }
        if (recipe instanceof CookingRecipe<?>) {
            return 3;
        }
        if (recipe instanceof StonecuttingRecipe) {
            return 4;
        }
        if (recipe instanceof SmithingRecipe) {
            return 5;
        }
        return 6;
    }

    /** 当前配方在某个 3×3 工作格（gridPos 0..8）里的原料，没有则 null。 */
    @SuppressWarnings("deprecation")
    private ItemStack ingredient(int windowSlot) {
        int gridPos = gridIndex(windowSlot);
        if (gridPos < 0) {
            return null;
        }
        Recipe recipe = current();
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            Map<Character, ItemStack> items = choices.isEmpty() ? shaped.getIngredientMap() : null;
            int row = gridPos / 3;
            int col = gridPos % 3;
            String line = row < shape.length && shape[row] != null ? shape[row] : "";
            char c = col < line.length() ? line.charAt(col) : ' ';
            if (c == ' ') {
                return null;
            }
            if (!choices.isEmpty()) {
                return choiceStack(choices.get(c));
            }
            return items == null ? null : items.get(c);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<RecipeChoice> choices = shapeless.getChoiceList();
            if (choices.isEmpty()) {
                List<ItemStack> items = shapeless.getIngredientList();
                return gridPos < items.size() ? items.get(gridPos) : null;
            }
            return gridPos < choices.size() ? choiceStack(choices.get(gridPos)) : null;
        }
        if (recipe instanceof TransmuteRecipe transmute) {
            if (gridPos == 3) {
                return choiceStack(transmute.getInput());
            }
            if (gridPos == 5) {
                return choiceStack(transmute.getMaterial());
            }
            return null;
        }
        if (recipe instanceof CookingRecipe<?> cooking) {
            return gridPos == 4 ? choiceStack(cooking.getInputChoice()) : null;
        }
        if (recipe instanceof StonecuttingRecipe stonecutting) {
            return gridPos == 4 ? choiceStack(stonecutting.getInputChoice()) : null;
        }
        if (recipe instanceof SmithingRecipe smithing) {
            if (gridPos == 3) {
                if (smithing instanceof SmithingTransformRecipe transform) {
                    return choiceStack(transform.getTemplate());
                }
                if (smithing instanceof SmithingTrimRecipe trim) {
                    return choiceStack(trim.getTemplate());
                }
                return null;
            }
            if (gridPos == 4) {
                return choiceStack(smithing.getBase());
            }
            if (gridPos == 5) {
                return choiceStack(smithing.getAddition());
            }
        }
        return null;
    }

    private static int gridIndex(int windowSlot) {
        for (int i = 0; i < GRID.length; i++) {
            if (GRID[i] == windowSlot) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack choiceStack(RecipeChoice choice) {
        if (choice == null) {
            return null;
        }
        try {
            return choice.getItemStack();
        } catch (Throwable t) {
            // 某些 RecipeChoice 取不到展示物品，忽略该格。
            return null;
        }
    }

    private ItemStack resultIcon(Recipe recipe) {
        ItemStack result = recipe.getResult().clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            if (recipes.size() > 1) {
                lore.add(Msg.color("&8配方 " + (index.get() + 1) + " / " + recipes.size()));
            }
            lore.add(Msg.color("&7产出 &f" + result.getAmount() + " &7个"));
            meta.setLore(lore);
            result.setItemMeta(meta);
        }
        return result;
    }

    private static ItemStack border() {
        return staticItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
    }

    private static ItemStack staticItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(Msg.color(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
