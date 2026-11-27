/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2015 the authors.
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

package uk.me.mantas.eternity.tests.serializer;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.CharacterStats;
import uk.me.mantas.eternity.game.DifficultyScaling;
import uk.me.mantas.eternity.game.PrerequisiteData;
import uk.me.mantas.eternity.game.PrerequisiteType;
import uk.me.mantas.eternity.game.StatusEffect;
import uk.me.mantas.eternity.game.StatusEffectParams;
import uk.me.mantas.eternity.serializer.*;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.Assert.*;

public class SerializerTest extends TestHarness {

	// These used to wrap their assertions in `try { … } catch (Exception e)
	// { e.printStackTrace(); }`, which swallowed the AssertionError too — they
	// could not fail. Anything thrown is now the test result.

	@Test
	public void serializesSaveFile () throws Exception {
		final File saveFile = new File(getClass().getResource("/MobileObjects.save").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.PRESERVE);
		assertFileContentsEquals(saveFile, saveOutputFile);
	}

	@Test
	public void serializesLevelFile () throws Exception {
		final File saveFile = new File(getClass().getResource("/SerializerTest/windowStoreSave/AR_0701_Encampment.lvl").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.PRESERVE);
		assertFileContentsEquals(saveFile, saveOutputFile);
	}

	@Test
	public void serializesWindowsStoreToSteamSaveFile () throws Exception {
		final File saveFile = new File(getClass().getResource("/SerializerTest/windowStoreSave/MobileObjects.save").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.UNITY_2017);

		final File expectedSaveFile = new File(getClass().getResource("/SerializerTest/windowStoreSaveConverted/MobileObjects.save").toURI());
		assertFileContentsEquals(expectedSaveFile, saveOutputFile);
	}

	// Newer versions of the game store enum values that this port's enum
	// definitions don't know about (e.g. CharacterStats+Class #53,
	// StatusEffect+ModifiedStat #282 in 2025-era saves). Unknown values must
	// survive a deserialize/reserialize round trip instead of being nulled.
	@Test
	public void preservesUnknownEnumValues () throws IOException {
		final int unknownValue = 999;
		final File file = Files.createTempFile(PREFIX, null).toFile();

		final SimpleProperty count = new SimpleProperty(
			"Root", new TypePair(int.class, "System.Int32"));
		count.value = 1;
		count.obj = 1;

		final SimpleProperty enumProperty = new SimpleProperty(
			"Root", new TypePair(CharacterStats.Class.class, "CharacterStats+Class"));
		enumProperty.value = unknownValue;
		enumProperty.obj = unknownValue;

		final SharpSerializer serializer = new SharpSerializer(file.getAbsolutePath());
		serializer.serialize(count);
		serializer.serialize(enumProperty);

		// The raw value must be preserved in memory...
		final SharpSerializer deserializer = new SharpSerializer(file.getAbsolutePath());
		assertTrue(deserializer.deserialize().isPresent()); // skip count
		final Optional<Property> read = deserializer.deserialize();
		assertTrue(read.isPresent());
		assertEquals(unknownValue, read.get().obj);

		// ...and the file must round trip byte-identically.
		final File output = Files.createTempFile(PREFIX, null).toFile();
		EKUtils.reserializeFile(file, output, SerializerFormat.PRESERVE);
		assertFileContentsEquals(file, output);
	}

	// PrerequisiteData.IsNot is a public simple bool field in Assembly-CSharp.dll,
	// so Polenter SharpSerializer writes it with an implicit (null) type header.
	// Without IsNot on the Java mirror, Deserializer passes expectedType=null and
	// fails to consume the bool bytes, desynchronising the rest of the packet.
	@Test
	public void deserializesPrerequisiteDataWithIsNot () throws IOException {
		final File file = Files.createTempFile(PREFIX, null).toFile();

		final SimpleProperty count = new SimpleProperty(
			"Root", new TypePair(int.class, "System.Int32"));
		count.value = 1;
		count.obj = 1;

		final ComplexProperty prereq = new ComplexProperty(
			"Root", new TypePair(PrerequisiteData.class, "PrerequisiteData, Assembly-CSharp"));

		// Simulate SharpSerializer omitting the type index when runtime type matches field type.
		final SimpleProperty isNot = new SimpleProperty("IsNot", new TypePair(boolean.class, null));
		isNot.value = true;
		isNot.obj = true;

		final SimpleProperty tag = new SimpleProperty("Tag", new TypePair(String.class, null));
		tag.value = "after-isnot";
		tag.obj = "after-isnot";

		prereq.properties.add(isNot);
		prereq.properties.add(tag);

		final SharpSerializer serializer = new SharpSerializer(file.getAbsolutePath());
		serializer.serialize(count);
		serializer.serialize(prereq);

		final SharpSerializer deserializer = new SharpSerializer(file.getAbsolutePath());
		assertTrue(deserializer.deserialize().isPresent());
		final Optional<Property> read = deserializer.deserialize();
		assertTrue(read.isPresent());
		final PrerequisiteData mirror = (PrerequisiteData) read.get().obj;
		assertTrue(mirror.IsNot);
		assertEquals("after-isnot", mirror.Tag);

		final File output = Files.createTempFile(PREFIX, null).toFile();
		EKUtils.reserializeFile(file, output, SerializerFormat.PRESERVE);
		assertFileContentsEquals(file, output);
	}

	// Enums with explicit C# values (public int n) must look up by n only, not
	// also by Java enum ordinal — otherwise an unmapped bitmask like 6
	// (ACT4_HIGH_LEVEL | ELMSHORE_HIGH_LEVEL) aliases ordinal 6 (PX2_HIGH_LEVEL=8)
	// and writes back as 8 instead of 6.
	@Test
	public void explicitValueEnumDoesNotAliasJavaOrdinal () throws IOException {
		final int unmappedBitmask = 6;
		final File file = Files.createTempFile(PREFIX, null).toFile();

		final SimpleProperty count = new SimpleProperty(
			"Root", new TypePair(int.class, "System.Int32"));
		count.value = 1;
		count.obj = 1;

		final SimpleProperty scaler = new SimpleProperty(
			"Root", new TypePair(DifficultyScaling.Scaler.class, "DifficultyScaling+Scaler, Assembly-CSharp"));
		scaler.value = unmappedBitmask;
		scaler.obj = unmappedBitmask;

		final SharpSerializer serializer = new SharpSerializer(file.getAbsolutePath());
		serializer.serialize(count);
		serializer.serialize(scaler);

		final SharpSerializer deserializer = new SharpSerializer(file.getAbsolutePath());
		assertTrue(deserializer.deserialize().isPresent());
		final Optional<Property> read = deserializer.deserialize();
		assertTrue(read.isPresent());
		assertEquals(unmappedBitmask, read.get().obj);

		final File output = Files.createTempFile(PREFIX, null).toFile();
		EKUtils.reserializeFile(file, output, SerializerFormat.PRESERVE);
		assertFileContentsEquals(file, output);
	}

	// Verify v3.10.0 enum values (ModifiedStat 279..283, Push=10000, Initiative=10001;
	// CharacterStats.Class 52..54; PrerequisiteType.HasAvailableAction;
	// StatusEffectParams.IntervalRateType.Potion and String AttackPrefabSerialized2).
	@Test
	public void deserializesCurrentGameEnumValuesAndStatusEffectParams () throws IOException {
		final File file = Files.createTempFile(PREFIX, null).toFile();

		final SimpleProperty count = new SimpleProperty(
			"Root", new TypePair(int.class, "System.Int32"));
		count.value = 4;
		count.obj = 4;

		final SimpleProperty push = new SimpleProperty(
			"Root", new TypePair(StatusEffect.ModifiedStat.class, "StatusEffect+ModifiedStat, Assembly-CSharp"));
		push.value = 10000;
		push.obj = 10000;

		final SimpleProperty initiative = new SimpleProperty(
			"Root", new TypePair(StatusEffect.ModifiedStat.class, "StatusEffect+ModifiedStat, Assembly-CSharp"));
		initiative.value = 10001;
		initiative.obj = 10001;

		final SimpleProperty kraken = new SimpleProperty(
			"Root", new TypePair(CharacterStats.Class.class, "CharacterStats+Class, Assembly-CSharp"));
		kraken.value = 52;
		kraken.obj = 52;

		final SimpleProperty playerTrap = new SimpleProperty(
			"Root", new TypePair(CharacterStats.Class.class, "CharacterStats+Class, Assembly-CSharp"));
		playerTrap.value = 53;
		playerTrap.obj = 53;

		final SharpSerializer serializer = new SharpSerializer(file.getAbsolutePath());
		serializer.serialize(count);
		serializer.serialize(push);
		serializer.serialize(initiative);
		serializer.serialize(kraken);
		serializer.serialize(playerTrap);

		final SharpSerializer deserializer = new SharpSerializer(file.getAbsolutePath());
		assertTrue(deserializer.deserialize().isPresent());
		assertEquals(StatusEffect.ModifiedStat.Push, deserializer.deserialize().get().obj);
		assertEquals(StatusEffect.ModifiedStat.Initiative, deserializer.deserialize().get().obj);
		assertEquals(CharacterStats.Class.Kraken, deserializer.deserialize().get().obj);
		assertEquals(CharacterStats.Class.PlayerTrap, deserializer.deserialize().get().obj);

		// Also verify PrerequisiteType, StatusEffect.ModifiedStat, and StatusEffectParams updates.
		assertEquals(279, StatusEffect.ModifiedStat.MeleeWeaponAccuracy.n);
		assertEquals(282, StatusEffect.ModifiedStat.BonusHitToCritRatioMeleeOneHand.n);
		assertEquals(10000, StatusEffect.ModifiedStat.Push.n);
		assertEquals(10001, StatusEffect.ModifiedStat.Initiative.n);
		assertNotNull(PrerequisiteType.HasAvailableAction);
		assertNotNull(StatusEffectParams.IntervalRateType.Potion);
		final StatusEffectParams params = new StatusEffectParams();
		params.AttackPrefabSerialized2 = "Assets/Data/Prefabs/Attacks/Example.prefab";
		params.TrapSerialized2 = "Assets/Data/Prefabs/Traps/Example.prefab";
		assertEquals("Assets/Data/Prefabs/Attacks/Example.prefab", params.AttackPrefabSerialized2);

		final File output = Files.createTempFile(PREFIX, null).toFile();
		EKUtils.reserializeFile(file, output, SerializerFormat.PRESERVE);
		assertFileContentsEquals(file, output);
	}

	public static void assertFileContentsEquals(File expectedFile, File actualFile) throws IOException {
		final byte[] actual = FileUtils.readFileToByteArray(actualFile);
		final byte[] expected = FileUtils.readFileToByteArray(expectedFile);

		assertArrayEquals(actualFile.getName() + " contents", expected, actual);
	}
}
