/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxram.lock.tcmd;

import de.hhu.bsinfo.dxram.data.ChunkID;
import de.hhu.bsinfo.dxram.lock.AbstractLockService;
import de.hhu.bsinfo.dxram.term.AbstractTerminalCommand;
import de.hhu.bsinfo.dxram.term.TerminalCommandContext;
import de.hhu.bsinfo.ethnet.NodeID;

/**
 * Unlock a previously locked chunk
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 03.04.2017
 */
public class TcmdChunkunlock extends AbstractTerminalCommand {
    public TcmdChunkunlock() {
        super("chunkunlock");
    }

    @Override
    public String getHelp() {
        return "Unlock a previously locked chunk\n" + "Usage (1): chunkunlock <cid>)\n" + "Usage (2): chunkunlock <nid, lid>\n" +
            "  cid: Full chunk ID of the chunk to unlock\n" + "  nid: Separate local id part of the chunk to unlock\n" +
            "  lid: Separate node id part of the chunk to unlock";
    }

    @Override
    public void exec(final String[] p_args, final TerminalCommandContext p_ctx) {
        long cid;

        if (p_args.length > 1) {
            short nid = TerminalCommandContext.getArgNodeId(p_args, 0, NodeID.INVALID_ID);
            long lid = p_ctx.getArgLocalId(p_args, 1, ChunkID.INVALID_ID);
            cid = ChunkID.getChunkID(nid, lid);
        } else {
            cid = TerminalCommandContext.getArgChunkId(p_args, 0, ChunkID.INVALID_ID);
        }

        if (cid == ChunkID.INVALID_ID) {
            p_ctx.printlnErr("No or invalid cid specified");
            return;
        }

        // don't allow removal of index chunk
        if (ChunkID.getLocalID(cid) == 0) {
            p_ctx.printlnErr("Locking/Unlocking of index chunk is not allowed");
            return;
        }

        AbstractLockService.ErrorCode err = p_ctx.getService(AbstractLockService.class).unlock(true, cid);
        if (err != AbstractLockService.ErrorCode.SUCCESS) {
            p_ctx.printflnErr("Error unlocking chunk 0x%X: %s", cid, err);
        } else {
            TerminalCommandContext.printfln("Unlocked chunk 0x%X", cid);
        }
    }
}