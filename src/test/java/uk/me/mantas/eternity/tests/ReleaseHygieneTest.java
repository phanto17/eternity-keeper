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

package uk.me.mantas.eternity.tests;

import org.joox.Match;
import org.junit.Test;
import uk.me.mantas.eternity.environment.AppVersion;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.joox.JOOX.$;
import static org.junit.Assert.*;

/**
 * Release packaging and licensing checks pinned in the test suite so they
 * cannot drift before or after a release tag.
 */
public class ReleaseHygieneTest extends TestHarness {
	@Test
	public void appVersionMatchesPomXml () throws Exception {
		final File pom = new File("pom.xml");
		assertTrue("run tests from eternity-keeper/", pom.isFile());

		final String pomVersion = $(pom).child("version").text().trim();
		assertFalse(pomVersion.isEmpty());
		assertEquals(pomVersion, AppVersion.current());

		final byte[] sample = ("version=9.8.7\n").getBytes(StandardCharsets.UTF_8);
		assertEquals("9.8.7", AppVersion.fromProperties(new ByteArrayInputStream(sample)));
	}

	@Test
	public void noProprietaryFontsInUiFolder () throws Exception {
		final File ui = new File("src/ui");
		assertTrue(ui.isDirectory());
		assertFalse("src/ui/fonts must not ship", new File(ui, "fonts").exists());

		try (Stream<java.nio.file.Path> walk = Files.walk(ui.toPath())) {
			final List<String> matches = walk
				.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(name -> name.toLowerCase().startsWith("proximanova"))
				.collect(Collectors.toList());

			assertTrue("proprietary font files found: " + matches, matches.isEmpty());
		}
	}

	@Test
	public void launcherNumericVersionMatchesPomVersion () throws Exception {
		final Match pom = $(new File("pom.xml"));
		final String pomVersion = pom.child("version").text().trim();
		final String base = pomVersion.split("-", 2)[0];
		final String[] parts = base.split("\\.");
		assertEquals("expected major.minor.patch in pom.xml version: " + pomVersion, 3, parts.length);

		final String expectedNumeric = base + ".0";
		assertEquals(expectedNumeric, pom.find("fileVersion").text().trim());
		assertEquals(expectedNumeric, pom.find("productVersion").text().trim());
	}
}
