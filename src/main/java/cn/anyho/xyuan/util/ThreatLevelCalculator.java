package cn.anyho.xyuan.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;

import java.util.Map;

/**
 * 威胁等级计算器：按 v2 规范评估玩家装备威胁等级。
 *
 * <p>v2 权重体系：护甲防御(4.0) + 护甲附魔(4.5) + 保命能力(1.5) 构成基础分(封顶10.0)，
 * 武器作为附加分(+1.0) 叠加后得到最终分(最高11.0)。
 * 输出梯队 S+/S/A/B/C/D/F 与胸甲显示名。所有读取在调用线程同步执行。</p>
 *
 * <p>1.21.2+ 工具类被移除，改用 {@link ItemTags} 标签判断武器类型。
 * v2 新增重锤(Mace)、矛(Spear，1.21.11+)、S+ 等级，细化武器材质分档与附魔互斥规则。</p>
 */
public final class ThreatLevelCalculator {

    /** 威胁评估结果。 */
    public record ThreatResult(
            double score,
            String tier,
            String chestplateName,
            String details,
            boolean isElytra
    ) {
    }

    private ThreatLevelCalculator() {
    }

    // ---------- 护甲防御分（v2 满分 4.0） ----------

    /** 各部位满分占比（v2：头盔15% 胸甲40% 护腿30% 靴子15%）。 */
    private static final double HELMET_MAX = 0.600;
    private static final double CHESTPLATE_MAX = 1.600;
    private static final double LEGGINGS_MAX = 1.200;
    private static final double BOOTS_MAX = 0.600;
    private static final double ARMOR_DEFENSE_CAP = 4.0;

    /** 各材质各部位护甲值速查（按原版数据，钻石为基准）。 */
    private static int armorValue(Item item, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> {
                if (item == Items.NETHERITE_HELMET || item == Items.DIAMOND_HELMET) yield 3;
                if (item == Items.IRON_HELMET || item == Items.CHAINMAIL_HELMET || item == Items.GOLDEN_HELMET) yield 2;
                if (item == Items.LEATHER_HELMET) yield 1;
                yield 0;
            }
            case CHEST -> {
                if (item == Items.NETHERITE_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE) yield 8;
                if (item == Items.IRON_CHESTPLATE || item == Items.GOLDEN_CHESTPLATE) yield 6;
                if (item == Items.CHAINMAIL_CHESTPLATE) yield 5;
                if (item == Items.LEATHER_CHESTPLATE) yield 3;
                yield 0;
            }
            case LEGS -> {
                if (item == Items.NETHERITE_LEGGINGS || item == Items.DIAMOND_LEGGINGS) yield 6;
                if (item == Items.IRON_LEGGINGS) yield 5;
                if (item == Items.CHAINMAIL_LEGGINGS) yield 4;
                if (item == Items.GOLDEN_LEGGINGS) yield 3;
                if (item == Items.LEATHER_LEGGINGS) yield 2;
                yield 0;
            }
            case FEET -> {
                if (item == Items.NETHERITE_BOOTS || item == Items.DIAMOND_BOOTS) yield 3;
                if (item == Items.IRON_BOOTS) yield 2;
                if (item == Items.CHAINMAIL_BOOTS || item == Items.GOLDEN_BOOTS || item == Items.LEATHER_BOOTS) yield 1;
                yield 0;
            }
            default -> 0;
        };
    }

    /** 钻石同部位护甲值（基准）。 */
    private static int diamondArmorValue(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 3;
            case CHEST -> 8;
            case LEGS -> 6;
            case FEET -> 3;
            default -> 0;
        };
    }

    /** 是否下界合金（额外 +10%）。 */
    private static boolean isNetherite(Item item) {
        return item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
                || item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS;
    }

    /** 单件护甲防御得分 = 部位满分 × (该材质护甲值 / 钻石护甲值) × (下界合金 × 1.1)。 */
    private static double armorDefenseScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        Item item = stack.getItem();
        int value = armorValue(item, slot);
        int diamondValue = diamondArmorValue(slot);
        if (diamondValue == 0 || value == 0) {
            return 0.0;
        }
        double slotMax = switch (slot) {
            case HEAD -> HELMET_MAX;
            case CHEST -> CHESTPLATE_MAX;
            case LEGS -> LEGGINGS_MAX;
            case FEET -> BOOTS_MAX;
            default -> 0.0;
        };
        double ratio = (double) value / diamondValue;
        double score = slotMax * ratio;
        if (isNetherite(item)) {
            score *= 1.1;
        }
        return score;
    }

    // ---------- 护甲附魔分（v2 满分 4.5） ----------

    private static final double PROTECTION_PER_LEVEL = 0.281;
    private static final double ELEMENTAL_PROTECTION_PER_LEVEL = 0.16875;
    private static final double FEATHER_FALLING_PER_LEVEL = 0.1125;
    private static final double ARMOR_ENCHANT_CAP = 4.5;

    /** 单件护甲附魔得分：取最高保护类附魔，靴子可叠加摔落保护。 */
    private static double armorEnchantScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        ItemEnchantmentsComponent enchants = getEnchantments(stack);
        if (enchants == null) {
            return 0.0;
        }

        double bestProtection = 0.0;
        double featherFalling = 0.0;

        for (Map.Entry<RegistryEntry<Enchantment>, Integer> entry : enchants.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> reg = entry.getKey();
            int level = entry.getValue();
            if (level <= 0) continue;

            String key = enchantKeyId(reg);
            double score = switch (key) {
                case "protection" -> PROTECTION_PER_LEVEL * level;
                case "fire_protection", "blast_protection", "projectile_protection" -> ELEMENTAL_PROTECTION_PER_LEVEL * level;
                case "feather_falling" -> FEATHER_FALLING_PER_LEVEL * level;
                default -> 0.0;
            };
            if (key.equals("feather_falling")) {
                featherFalling = Math.max(featherFalling, score);
            } else if (score > bestProtection) {
                bestProtection = score;
            }
        }

        // 靴子可叠加摔落保护
        return slot == EquipmentSlot.FEET ? (bestProtection + featherFalling) : bestProtection;
    }

    // ---------- 武器附加分（v2 附加分 +1.0，基础最高0.60 + 附魔最高0.40） ----------

    private static final double WEAPON_BONUS_CAP = 1.0;

    /** 武器材质基础分（v2：以下界合金剑 0.55 为基准，重锤 0.60 为最高）。 */
    private static double weaponBaseScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.05; // 徒手
        }
        Item item = stack.getItem();

        // 重锤（1.21+ 新武器，基础分最高）
        if (item == Items.MACE) return 0.60;

        // 剑类（v2 材质分档，基于合金剑 0.55 基准）
        if (item == Items.NETHERITE_SWORD) return 0.55;
        if (item == Items.DIAMOND_SWORD) return 0.49;
        if (item == Items.IRON_SWORD) return 0.42;
        if (item == Items.STONE_SWORD) return 0.35;
        if (item == Items.WOODEN_SWORD || item == Items.GOLDEN_SWORD) return 0.28;

        // 斧类
        if (item == Items.NETHERITE_AXE) return 0.52;
        if (item == Items.DIAMOND_AXE) return 0.47;
        if (item == Items.IRON_AXE) return 0.43;
        if (item == Items.STONE_AXE) return 0.40;
        if (item == Items.WOODEN_AXE || item == Items.GOLDEN_AXE) return 0.37;

        // 三叉戟（v2 基础分 0.55）
        if (item == Items.TRIDENT) return 0.55;

        // 镐类
        if (item == Items.NETHERITE_PICKAXE) return 0.26;
        if (item == Items.DIAMOND_PICKAXE) return 0.22;
        if (item == Items.IRON_PICKAXE) return 0.17;
        if (item == Items.STONE_PICKAXE) return 0.13;
        if (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) return 0.09;

        // 锹类
        if (item == Items.NETHERITE_SHOVEL) return 0.26;
        if (item == Items.DIAMOND_SHOVEL) return 0.20;
        if (item == Items.IRON_SHOVEL) return 0.17;
        if (item == Items.STONE_SHOVEL) return 0.13;
        if (item == Items.WOODEN_SHOVEL || item == Items.GOLDEN_SHOVEL) return 0.09;

        // 弓/弩
        if (item == Items.BOW || item == Items.CROSSBOW) return 0.25;

        // 矛类（1.21.11+ 或模组添加）：按注册名匹配材质分档
        String itemPath = itemRegistryPath(item);
        if (itemPath.contains("spear")) {
            if (itemPath.contains("netherite")) return 0.37;
            if (itemPath.contains("diamond")) return 0.30;
            if (itemPath.contains("iron")) return 0.22;
            if (itemPath.contains("stone") || itemPath.contains("copper")) return 0.15;
            return 0.08; // 木/金/其他
        }

        // 锄类（任意锄 0.03）
        if (itemPath.contains("hoe")) return 0.03;

        // 其他物品按标签兜底（用 ItemStack.isIn 避免 getRegistryEntry 弃用）
        if (isInTag(stack, ItemTags.SWORDS)) return 0.28;
        if (isInTag(stack, ItemTags.AXES)) return 0.37;
        if (isInTag(stack, ItemTags.PICKAXES)) return 0.09;
        if (isInTag(stack, ItemTags.SHOVELS)) return 0.09;

        return 0.05; // 徒手 / 其他
    }

    /** 获取物品注册路径名（如 "netherite_sword"），异常时返回空串。 */
    private static String itemRegistryPath(Item item) {
        try {
            return Registries.ITEM.getId(item).getPath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 安全的标签判断（用 ItemStack.isIn，异常时返回 false）。 */
    private static boolean isInTag(ItemStack stack, net.minecraft.registry.tag.TagKey<Item> tag) {
        try {
            return stack.isIn(tag);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 武器附魔分（v2 最高 0.40）：
     * 锋利类互斥取最高 + 火焰附加 + 横扫之刃（剑）；
     * 致密/破甲互斥取最高 + 风爆可叠加（重锤）；
     * 穿刺 + 激流/忠诚/引雷（三叉戟，激流与忠诚/引雷互斥）；
     * 突进（矛）。
     */
    private static double weaponEnchantScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        ItemEnchantmentsComponent enchants = getEnchantments(stack);
        if (enchants == null) {
            return 0.0;
        }

        double bestSharpness = 0.0;    // 锋利/亡灵/节肢 互斥取最高
        double fireAspect = 0.0;       // 火焰附加
        double sweeping = 0.0;         // 横扫之刃
        double bestMaceMutual = 0.0;   // 致密/破甲 互斥取最高
        double windBurst = 0.0;        // 风爆（可叠加）
        double rush = 0.0;             // 突进（矛）
        double impaling = 0.0;         // 穿刺（三叉戟）
        double riptide = 0.0;          // 激流（三叉戟）
        double loyalty = 0.0;          // 忠诚（三叉戟）
        double channeling = 0.0;       // 引雷（三叉戟）

        for (Map.Entry<RegistryEntry<Enchantment>, Integer> entry : enchants.getEnchantmentEntries()) {
            String key = enchantKeyId(entry.getKey());
            int level = entry.getValue();
            if (level <= 0) continue;

            switch (key) {
                // 锋利类互斥（三选一）
                case "sharpness" -> bestSharpness = Math.max(bestSharpness, 0.06 * level);
                case "smite", "bane_of_arthropods" -> bestSharpness = Math.max(bestSharpness, 0.03 * level);
                // 火焰附加（剑/重锤通用）
                case "fire_aspect" -> fireAspect = Math.max(fireAspect, 0.05 * level);
                // 横扫之刃（剑）
                case "sweeping_edge" -> sweeping = Math.max(sweeping, 0.025 * level);
                // 重锤附魔：致密/破甲互斥
                case "density" -> bestMaceMutual = Math.max(bestMaceMutual, 0.06 * level);
                case "breach" -> bestMaceMutual = Math.max(bestMaceMutual, 0.05 * level);
                // 风爆（重锤，可叠加）
                case "wind_burst" -> windBurst = Math.max(windBurst, 0.05 * level);
                // 突进（矛）
                case "rush" -> rush = Math.max(rush, 0.05 * level);
                // 三叉戟附魔
                case "impaling" -> impaling = Math.max(impaling, 0.05 * level);
                case "riptide" -> riptide = Math.max(riptide, 0.033 * level);
                case "loyalty" -> loyalty = Math.max(loyalty, 0.017 * level);
                case "channeling" -> channeling = Math.max(channeling, 0.05 * level);
                default -> { /* 忽略其他附魔 */ }
            }
        }

        // 激流与忠诚/引雷互斥：有激流时忽略忠诚和引雷
        double tridentBonus = impaling + riptide;
        if (riptide <= 0) {
            tridentBonus += loyalty + channeling;
        }

        return bestSharpness + fireAspect + sweeping + bestMaceMutual + windBurst + rush + tridentBonus;
    }

    // ---------- 保命分（v2 满分 1.5） ----------

    /** 保命分：副手图腾 1.5 / 仅主手图腾 0.75 / 无 0.0。 */
    private static double survivalScore(ItemStack mainHand, ItemStack offHand) {
        boolean offTotem = !offHand.isEmpty() && offHand.getItem() == Items.TOTEM_OF_UNDYING;
        boolean mainTotem = !mainHand.isEmpty() && mainHand.getItem() == Items.TOTEM_OF_UNDYING;
        if (offTotem) return 1.5;
        if (mainTotem) return 0.75;
        return 0.0;
    }

    // ---------- 主入口 ----------

    /** 计算玩家威胁等级。读取装备在调用线程同步执行。 */
    public static ThreatResult calculate(PlayerEntity player) {
        ItemStack helmet = player.getEquippedStack(EquipmentSlot.HEAD);
        ItemStack chestplate = player.getEquippedStack(EquipmentSlot.CHEST);
        ItemStack leggings = player.getEquippedStack(EquipmentSlot.LEGS);
        ItemStack boots = player.getEquippedStack(EquipmentSlot.FEET);
        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();

        // 鞘翅特殊处理
        boolean isElytra = !chestplate.isEmpty() && chestplate.getItem() == Items.ELYTRA;

        // 1. 护甲防御分（v2 封顶 4.0）
        double armorDefense = armorDefenseScore(helmet, EquipmentSlot.HEAD)
                + (isElytra ? 0.0 : armorDefenseScore(chestplate, EquipmentSlot.CHEST))
                + armorDefenseScore(leggings, EquipmentSlot.LEGS)
                + armorDefenseScore(boots, EquipmentSlot.FEET);
        armorDefense = Math.min(armorDefense, ARMOR_DEFENSE_CAP);

        // 2. 护甲附魔分（v2 封顶 4.5）
        double armorEnchant = armorEnchantScore(helmet, EquipmentSlot.HEAD)
                + armorEnchantScore(chestplate, EquipmentSlot.CHEST)
                + armorEnchantScore(leggings, EquipmentSlot.LEGS)
                + armorEnchantScore(boots, EquipmentSlot.FEET);
        armorEnchant = Math.min(armorEnchant, ARMOR_ENCHANT_CAP);

        // 3. 保命分（v2：副手图腾 1.5 / 主手图腾 0.75 / 无 0.0）
        double survival = survivalScore(mainHand, offHand);

        // 4. 武器附加分（v2：主手图腾时为 0，否则 base+enchant 封顶 1.0）
        boolean mainIsTotem = !mainHand.isEmpty() && mainHand.getItem() == Items.TOTEM_OF_UNDYING;
        double weaponBonus = mainIsTotem ? 0.0
                : Math.min(weaponBaseScore(mainHand) + weaponEnchantScore(mainHand), WEAPON_BONUS_CAP);

        // 5. 总分（v2 公式：基础分封顶 10.0 + 武器附加分，可突破 10.0 达到 S+）
        double baseTotal = Math.min(armorDefense + armorEnchant + survival, 10.0);
        double finalTotal = baseTotal + weaponBonus;
        String tier = tierOf(finalTotal);

        String chestName = chestplateName(chestplate, isElytra);
        String details = buildDetails(helmet, chestplate, leggings, boots, mainHand, offHand, isElytra);

        return new ThreatResult(finalTotal, tier, chestName, details, isElytra);
    }

    /** 梯队映射（v2 新增 S+：> 10.1）。 */
    private static String tierOf(double score) {
        if (score > 10.1) return "S+";
        if (score >= 9.0) return "S";
        if (score >= 7.0) return "A";
        if (score >= 5.0) return "B";
        if (score >= 3.5) return "C";
        if (score >= 1.5) return "D";
        return "F";
    }

    /** 胸甲显示名。 */
    private static String chestplateName(ItemStack chestplate, boolean isElytra) {
        if (isElytra) return "鞘翅";
        if (chestplate.isEmpty()) return "无";
        Item item = chestplate.getItem();
        if (item == Items.NETHERITE_CHESTPLATE) return "下界合金甲";
        if (item == Items.DIAMOND_CHESTPLATE) return "钻石甲";
        if (item == Items.IRON_CHESTPLATE) return "铁甲";
        if (item == Items.CHAINMAIL_CHESTPLATE) return "锁链甲";
        if (item == Items.GOLDEN_CHESTPLATE) return "金甲";
        if (item == Items.LEATHER_CHESTPLATE) return "皮革甲";
        return "其他";
    }

    /** 构造完整装备详情字符串。 */
    private static String buildDetails(ItemStack helmet, ItemStack chestplate, ItemStack leggings,
                                       ItemStack boots, ItemStack mainHand, ItemStack offHand, boolean isElytra) {
        return "头盔:" + itemName(helmet) + enchantSummary(helmet) + "\n"
                + "胸甲:" + (isElytra ? "鞘翅" : itemName(chestplate)) + enchantSummary(chestplate) + "\n"
                + "护腿:" + itemName(leggings) + enchantSummary(leggings) + "\n"
                + "靴子:" + itemName(boots) + enchantSummary(boots) + "\n"
                + "主手:" + itemName(mainHand) + enchantSummary(mainHand) + "\n"
                + "副手:" + itemName(offHand);
    }

    /** 物品中文名（仅常见装备/武器，其他返回注册路径名）。 */
    private static String itemName(ItemStack stack) {
        if (stack.isEmpty()) return "无";
        Item item = stack.getItem();
        if (item == Items.NETHERITE_HELMET) return "下界合金头盔";
        if (item == Items.NETHERITE_CHESTPLATE) return "下界合金胸甲";
        if (item == Items.NETHERITE_LEGGINGS) return "下界合金护腿";
        if (item == Items.NETHERITE_BOOTS) return "下界合金靴子";
        if (item == Items.NETHERITE_SWORD) return "下界合金剑";
        if (item == Items.NETHERITE_AXE) return "下界合金斧";
        if (item == Items.DIAMOND_HELMET) return "钻石头盔";
        if (item == Items.DIAMOND_CHESTPLATE) return "钻石胸甲";
        if (item == Items.DIAMOND_LEGGINGS) return "钻石护腿";
        if (item == Items.DIAMOND_BOOTS) return "钻石靴子";
        if (item == Items.DIAMOND_SWORD) return "钻石剑";
        if (item == Items.DIAMOND_AXE) return "钻石斧";
        if (item == Items.IRON_HELMET) return "铁头盔";
        if (item == Items.IRON_CHESTPLATE) return "铁胸甲";
        if (item == Items.IRON_LEGGINGS) return "铁护腿";
        if (item == Items.IRON_BOOTS) return "铁靴子";
        if (item == Items.IRON_SWORD) return "铁剑";
        if (item == Items.IRON_AXE) return "铁斧";
        if (item == Items.CHAINMAIL_HELMET) return "锁链头盔";
        if (item == Items.CHAINMAIL_CHESTPLATE) return "锁链胸甲";
        if (item == Items.CHAINMAIL_LEGGINGS) return "锁链护腿";
        if (item == Items.CHAINMAIL_BOOTS) return "锁链靴子";
        if (item == Items.GOLDEN_HELMET) return "金头盔";
        if (item == Items.GOLDEN_CHESTPLATE) return "金胸甲";
        if (item == Items.GOLDEN_LEGGINGS) return "金护腿";
        if (item == Items.GOLDEN_BOOTS) return "金靴子";
        if (item == Items.GOLDEN_SWORD) return "金剑";
        if (item == Items.GOLDEN_AXE) return "金斧";
        if (item == Items.LEATHER_HELMET) return "皮革头盔";
        if (item == Items.LEATHER_CHESTPLATE) return "皮革外套";
        if (item == Items.LEATHER_LEGGINGS) return "皮革裤子";
        if (item == Items.LEATHER_BOOTS) return "皮革靴子";
        if (item == Items.ELYTRA) return "鞘翅";
        if (item == Items.TOTEM_OF_UNDYING) return "不死图腾";
        if (item == Items.TRIDENT) return "三叉戟";
        if (item == Items.MACE) return "重锤";
        if (item == Items.BOW) return "弓";
        if (item == Items.CROSSBOW) return "弩";
        if (item == Items.SHIELD) return "盾牌";
        if (item == Items.STONE_SWORD) return "石剑";
        if (item == Items.STONE_AXE) return "石斧";
        if (item == Items.WOODEN_SWORD) return "木剑";
        if (item == Items.WOODEN_AXE) return "木斧";
        // 兜底：用注册表路径名
        String path = itemRegistryPath(item);
        return path.isEmpty() ? item.toString() : path;
    }

    /** 附魔摘要，如 " [保护 IV, 摔落保护 IV]"。无附魔返回空字符串。 */
    private static String enchantSummary(ItemStack stack) {
        if (stack.isEmpty()) return "";
        ItemEnchantmentsComponent enchants = getEnchantments(stack);
        if (enchants == null || enchants.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(" [");
        boolean first = true;
        for (Map.Entry<RegistryEntry<Enchantment>, Integer> entry : enchants.getEnchantmentEntries()) {
            if (!first) sb.append(", ");
            sb.append(enchantChineseName(enchantKeyId(entry.getKey()))).append(" ").append(romanLevel(entry.getValue()));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /** 罗马数字（1-5）。 */
    private static String romanLevel(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    /** 附魔键名 → 中文。 */
    private static String enchantChineseName(String key) {
        return switch (key) {
            case "protection" -> "保护";
            case "fire_protection" -> "火焰保护";
            case "blast_protection" -> "爆炸保护";
            case "projectile_protection" -> "弹射物保护";
            case "feather_falling" -> "摔落保护";
            case "sharpness" -> "锋利";
            case "smite" -> "亡灵杀手";
            case "bane_of_arthropods" -> "节肢杀手";
            case "fire_aspect" -> "火焰附加";
            case "sweeping_edge" -> "横扫之刃";
            case "density" -> "致密";
            case "breach" -> "破甲";
            case "wind_burst" -> "风爆";
            case "rush" -> "突进";
            case "impaling" -> "穿刺";
            case "riptide" -> "激流";
            case "loyalty" -> "忠诚";
            case "channeling" -> "引雷";
            case "unbreaking" -> "耐久";
            case "mending" -> "经验修补";
            case "thorns" -> "荆棘";
            case "respiration" -> "水下呼吸";
            case "aqua_affinity" -> "水下速掘";
            case "depth_strider" -> "深海探索者";
            case "frost_walker" -> "冰霜行者";
            case "soul_speed" -> "灵魂疾行";
            case "swift_sneak" -> "迅捷潜行";
            case "power" -> "力量";
            case "punch" -> "冲击";
            case "flame" -> "火矢";
            case "infinity" -> "无限";
            case "multishot" -> "多重射击";
            case "quick_charge" -> "快速装填";
            case "piercing" -> "穿透";
            default -> key;
        };
    }

    /** 读取附魔组件（1.21+ 使用 DataComponentTypes.ENCHANTMENTS）。 */
    private static ItemEnchantmentsComponent getEnchantments(ItemStack stack) {
        try {
            return stack.get(DataComponentTypes.ENCHANTMENTS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从 RegistryEntry<Enchantment> 提取附魔键名（去掉 minecraft: 前缀）。 */
    private static String enchantKeyId(RegistryEntry<Enchantment> reg) {
        try {
            return reg.getKey()
                    .map(k -> k.getValue().toString().replace("minecraft:", ""))
                    .orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
