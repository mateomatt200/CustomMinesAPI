package com.venned.simplemines.api;

import com.venned.simplemines.build.Mine;
import com.venned.simplemines.manager.MineManager;
import org.bukkit.Location;

public class MineImpl implements IMine {
    private final MineManager mineManager;

    public MineImpl(MineManager mineManager) {
        this.mineManager = mineManager;
    }

    @Override
    public MineManager getManager() {
        return mineManager;
    }
}