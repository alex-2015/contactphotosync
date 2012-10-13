/**
 * Util.java - Utility stuff.
 * 
 * Copyright (C) 2012 Mansour <mansour@oxplot.com>
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.oxplot.contactphotosync;

import java.io.DataOutputStream;
import java.io.IOException;

public class Util {
  public static boolean runRoot(String dataIn) {
    Process p = null;

    try {
      p = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(p.getOutputStream());

      if (dataIn != null)
        os.writeBytes(dataIn);

      os.writeBytes("\nexit\n");
      os.flush();
      try {
        p.waitFor();
        if (p.exitValue() == 0) {
          return true;
        } else {
          return false;
        }
      } catch (InterruptedException e) {
        return false;
      }

    } catch (IOException e) {
      return false;
    } finally {
      if (p != null)
        p.destroy();
    }
  }
}
