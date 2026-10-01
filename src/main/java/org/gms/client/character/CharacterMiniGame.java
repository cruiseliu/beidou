package org.gms.client.character;

import org.gms.server.maps.MiniGame;
import org.gms.server.maps.MiniGame.MiniGameResult;
import org.gms.server.minigame.RockPaperScissor;

/**
 * 小游戏模块组件：迷你游戏（MiniGame 五子棋/记忆卡）+ 猜拳（RPS）+ 对局计分（omok/matchcard）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getMiniGame/closeMiniGame/getMiniGamePoints/... 对外转发）。
 *
 * 边界：只承载小游戏语义——迷你游戏实例、猜拳实例、胜/负/平计分（persist characters 表）。
 * 依赖经 owner 门面调用（getClient/...）。
 */
class CharacterMiniGame {
    private final Character owner;

    /** 迷你游戏实例（五子棋/记忆卡） */
    private MiniGame miniGame;

    /** 猜拳实例 */
    private RockPaperScissor rps;

    // 五子棋计分
    private int omokwins;
    private int omokties;
    private int omoklosses;

    // 记忆卡计分
    private int matchcardwins;
    private int matchcardties;
    private int matchcardlosses;

    CharacterMiniGame(Character owner) {
        this.owner = owner;
    }

    // ── 实例 ──

    MiniGame getMiniGame() {
        return miniGame;
    }

    void setMiniGame(MiniGame miniGame) {
        this.miniGame = miniGame;
    }

    RockPaperScissor getRPS() {
        return rps;
    }

    void setRPS(RockPaperScissor rps) {
        this.rps = rps;
    }

    void closeMiniGame(boolean forceClose) {
        MiniGame game = this.getMiniGame();
        if (game == null) {
            return;
        }

        if (game.isOwner(owner)) {
            game.closeRoom(forceClose);
        } else {
            game.removeVisitor(forceClose, owner);
        }
    }

    void closeRPS() {
        RockPaperScissor rps = this.rps;
        if (rps != null) {
            rps.dispose(owner.client);
            setRPS(null);
        }
    }

    // ── 计分 ──

    int getMiniGamePoints(MiniGameResult type, boolean omok) {
        if (omok) {
            return switch (type) {
                case WIN -> omokwins;
                case LOSS -> omoklosses;
                default -> omokties;
            };
        } else {
            return switch (type) {
                case WIN -> matchcardwins;
                case LOSS -> matchcardlosses;
                default -> matchcardties;
            };
        }
    }

    void setMiniGamePoints(Character visitor, int winnerslot, boolean omok) {
        if (omok) {
            if (winnerslot == 1) {
                this.omokwins++;
                visitor.miniGame.omoklosses++;
            } else if (winnerslot == 2) {
                visitor.miniGame.omokwins++;
                this.omoklosses++;
            } else {
                this.omokties++;
                visitor.miniGame.omokties++;
            }
        } else {
            if (winnerslot == 1) {
                this.matchcardwins++;
                visitor.miniGame.matchcardlosses++;
            } else if (winnerslot == 2) {
                visitor.miniGame.matchcardwins++;
                this.matchcardlosses++;
            } else {
                this.matchcardties++;
                visitor.miniGame.matchcardties++;
            }
        }
    }

    // 五子棋计分 getter/setter

    int getOmokwins() {
        return omokwins;
    }

    void setOmokwins(int omokwins) {
        this.omokwins = omokwins;
    }

    int getOmokties() {
        return omokties;
    }

    void setOmokties(int omokties) {
        this.omokties = omokties;
    }

    int getOmoklosses() {
        return omoklosses;
    }

    void setOmoklosses(int omoklosses) {
        this.omoklosses = omoklosses;
    }

    // 记忆卡计分 getter/setter

    int getMatchcardwins() {
        return matchcardwins;
    }

    void setMatchcardwins(int matchcardwins) {
        this.matchcardwins = matchcardwins;
    }

    int getMatchcardties() {
        return matchcardties;
    }

    void setMatchcardties(int matchcardties) {
        this.matchcardties = matchcardties;
    }

    int getMatchcardlosses() {
        return matchcardlosses;
    }

    void setMatchcardlosses(int matchcardlosses) {
        this.matchcardlosses = matchcardlosses;
    }
}
