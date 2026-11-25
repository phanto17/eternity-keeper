/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2026 the authors.
 *
 *  Eternity Keeper is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Eternity Keeper is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.me.mantas.eternity.environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.joox.JOOX.$;

/**
 * The running Eternity Keeper version, read from the packaged Maven metadata
 * or, when running from unpacked classes in a checkout, from {@code pom.xml}.
 */
public final class AppVersion {
	private static final String POM_PROPERTIES = "/META-INF/maven/uk.me.mantas/eternity/pom.properties";
	private static final String UNKNOWN = "unknown";

	private AppVersion () {}

	public static String current () {
		try (final InputStream stream = AppVersion.class.getResourceAsStream(POM_PROPERTIES)) {
			if (stream != null) {
				final String packaged = fromProperties(stream);
				if (!UNKNOWN.equals(packaged)) {
					return packaged;
				}
			}
		} catch (final IOException ignored) {
		}

		final File home = AppPaths.forThisProcess().home();
		for (final File dir : new File[] {home, home.getParentFile(), new File(".")}) {
			if (dir == null) {
				continue;
			}

			final File pom = new File(dir, "pom.xml");
			if (pom.isFile()) {
				try {
					final String version = $(pom).child("version").text();
					if (version != null && !version.trim().isEmpty()) {
						return version.trim();
					}
				} catch (final Exception ignored) {
				}
			}
		}

		return UNKNOWN;
	}

	/** Reads the {@code version} entry from a {@code pom.properties} stream. */
	public static String fromProperties (final InputStream stream) throws IOException {
		if (stream == null) {
			return UNKNOWN;
		}

		final Properties properties = new Properties();
		properties.load(stream);
		final String version = properties.getProperty("version", "").trim();
		return version.isEmpty() ? UNKNOWN : version;
	}
}
