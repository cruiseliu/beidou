package org.gms.client.character;

import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.CharactersDO;
import org.gms.exception.NotEnabledException;
import org.gms.util.I18nUtil;

import java.util.Optional;

/**
 * 转生模块组件：转生次数（reborns，直接读写 characters 表）+ 转生执行（executeRebornAs）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getReborns/executeRebornAs/... 对外转发）。
 *
 * 边界：只承载转生语义——转生计数与转生执行（重设等级/转职）。
 * 怪物重生（respawn）不属本组件；
 * 依赖经 owner 门面调用（yellowMessage/getLevel/setLevel/getMaxClassLevel/changeJob/levelUp/...）。
 */
class CharacterRebirth {
    private final Character owner;

    CharacterRebirth(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    void setReborns(int value) {
        if (!GameConfig.getServerBoolean("use_rebirth_system")) {
            owner.yellowMessage(I18nUtil.getMessage("Character.USE_REBIRTH_SYSTEM")); //重生系统未启用
            throw new NotEnabledException();
        }

        Character.characterService.update(CharactersDO.builder()
                .id(owner.getId())
                .reborns(value)
                .build());
    }

    void addReborns() {
        setReborns(getReborns() + 1);
    }

    int getReborns() {
        if (!GameConfig.getServerBoolean("use_rebirth_system")) {
            owner.yellowMessage(I18nUtil.getMessage("Character.USE_REBIRTH_SYSTEM")); //重生系统未启用
            throw new NotEnabledException();
        }

        CharactersDO charactersDO = Character.characterService.findById(owner.getId());
        return charactersDO == null ? 0 : Optional.ofNullable(charactersDO.getReborns()).orElse(0);
    }

    // ── 执行 ──

    void executeRebornAsId(int jobId) {
        executeRebornAs(Job.getById(jobId));
    }

    void executeRebornAs(Job job) {
        if (!GameConfig.getServerBoolean("use_rebirth_system")) {
            owner.yellowMessage(I18nUtil.getMessage("Character.USE_REBIRTH_SYSTEM")); //重生系统未启用
            throw new NotEnabledException();
        }
        if (owner.getLevel() != owner.getMaxClassLevel()) {
            return;
        }
        addReborns();
        owner.changeJob(job);
        owner.setLevel(0);
        owner.levelUp(true);
    }
}
