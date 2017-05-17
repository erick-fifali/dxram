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

package de.hhu.bsinfo.dxram.log.tcmd;

import de.hhu.bsinfo.dxram.log.LogService;
import de.hhu.bsinfo.dxram.term.AbstractTerminalCommand;
import de.hhu.bsinfo.dxram.term.TerminalCommandContext;
import de.hhu.bsinfo.ethnet.NodeID;

/**
 * Prints the log utilization of given peer
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 03.04.2017
 */
public class TcmdLoginfo extends AbstractTerminalCommand {
    public TcmdLoginfo() {
        super("loginfo");
    }

    @Override
    public String getHelp() {
        return "Prints the log utilization of given peer\n" + "Usage: loginfo <nid>\n" + "  nid: Node id of the peer";
    }

    @Override
    public void exec(final String[] p_args, final TerminalCommandContext p_ctx) {
        short nid = TerminalCommandContext.getArgNodeId(p_args, 0, NodeID.INVALID_ID);

        if (nid == NodeID.INVALID_ID) {
            TerminalCommandContext.printlnErr("None or invalid nid specified");
            return;
        }

        LogService log = p_ctx.getService(LogService.class);

        TerminalCommandContext.println(log.getCurrentUtilization(nid));
    }
}