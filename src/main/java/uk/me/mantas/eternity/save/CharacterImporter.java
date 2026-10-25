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


package uk.me.mantas.eternity.save;

import org.json.JSONObject;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

public class CharacterImporter {
	private static final Logger logger = Logger.getLogger(CharacterImporter.class);
	private final File opened;
	private final boolean savedYet;
	private final File chrFile;
	private final PacketDeserializerFactory packetDeserializer;

	public CharacterImporter (final String request, final String chrFilePath)
		throws FileNotFoundException {

		final Environment environment = Environment.getInstance();
		final JSONObject json = new JSONObject(request);
		opened = new File(json.getString("oldSave"));
		savedYet = json.getBoolean("savedYet");

		if (!saveFile().exists()) {
			throw new FileNotFoundException(saveFile().getAbsolutePath());
		}

		final File chrFile = new File(chrFilePath);
		if (!chrFile.exists()) {
			throw new FileNotFoundException(chrFilePath);
		}

		this.chrFile = chrFile;
		packetDeserializer = environment.factory().packetDeserializer();
	}

	/**
	 * The save as it stands now: the one the list opened, the private copy an
	 * edit made of it, or the one last written. Asking for a conflict only
	 * reads; the import itself is what makes the copy.
	 */
	public File saveFile () {
		return Environment.getInstance().state().workingSave().forReading(opened, savedYet);
	}

	// Describes a character in the CHR file that already exists in the target
	// save and would be duplicated by a plain import.
	public static final class ImportConflict {
		public final String characterName;
		public final boolean mainCharacter;

		ImportConflict (final String characterName, final boolean mainCharacter) {
			this.characterName = characterName;
			this.mainCharacter = mainCharacter;
		}
	}

	public Optional<ImportConflict> detectConflict () throws IOException {
		final Optional<DeserializedPackets> chrDeserialized =
			packetDeserializer.forFile(chrFile).deserialize();

		if (!chrDeserialized.isPresent()) {
			return Optional.empty();
		}

		final Optional<Property> chrCharacter =
			findCharacter(chrDeserialized.get().getPackets());

		if (!chrCharacter.isPresent()) {
			return Optional.empty();
		}

		final File mobileObjectsFile = new File(saveFile(), "MobileObjects.save");
		final Optional<DeserializedPackets> deserialized =
			packetDeserializer.forFile(mobileObjectsFile).deserialize();

		if (!deserialized.isPresent()) {
			return Optional.empty();
		}

		final ObjectPersistencePacket chrPacket =
			(ObjectPersistencePacket) chrCharacter.get().obj;

		return findExistingMatch(chrPacket, deserialized.get().getPackets())
			.map(existing -> new ImportConflict(
				EKUtils.extractCharacterName(chrPacket.ObjectName)
				, isMainCharacter(chrPacket)));
	}

	public boolean importCharacter () throws IOException {
		return doImport(false);
	}

	public boolean overwriteCharacter () throws IOException {
		return doImport(true);
	}

	private boolean doImport (final boolean overwrite) throws IOException {
		final PacketDeserializer chrDeserializer = packetDeserializer.forFile(chrFile);
		final Optional<DeserializedPackets> chrDeserialized = chrDeserializer.deserialize();
		if (!chrDeserialized.isPresent()) {
			return false;
		}

		final List<Property> chrObjects = chrDeserialized.get().getPackets();
		if (chrObjects.size() < 1) {
			return false;
		}

		final File editing = Environment.getInstance().state().workingSave()
			.forEditing(opened, savedYet);
		final File mobileObjectsFile = new File(editing, "MobileObjects.save");
		final PacketDeserializer deserializer = packetDeserializer.forFile(mobileObjectsFile);
		final Optional<DeserializedPackets> deserialized = deserializer.deserialize();
		if (!deserialized.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final List<Property> mobileObjects = deserialized.get().getPackets();
		if (mobileObjects.size() < 1) {
			logger.error("No objects in MobileObjects.save.%n");
			return false;
		}

		final Optional<Property> characterProperty = findCharacter(chrObjects);
		if (!characterProperty.isPresent()) {
			logger.error("Unable to find character in CHR file.%n");
			return false;
		}

		final ObjectPersistencePacket chrPacket =
			(ObjectPersistencePacket) characterProperty.get().obj;

		final Optional<Property> existingMatch = overwrite
			? findExistingMatch(chrPacket, mobileObjects)
			: Optional.empty();

		List<Property> retainedObjects = mobileObjects;
		final String levelName;
		final Vector3 location;

		if (existingMatch.isPresent()) {
			// Overwrite: the imported character replaces the existing one
			// (and everything it owns) and takes its exact position. Its
			// identity is kept so no GUID regeneration happens.
			final ObjectPersistencePacket existing =
				(ObjectPersistencePacket) existingMatch.get().obj;

			retainedObjects = removeCharacterObjects(mobileObjects, existingMatch.get(), existing);
			levelName = existing.LevelName;
			location = existing.Location;

			// The game keeps the playthrough's ID on the main character, and
			// names the save's autosaves by it. A Watcher from another
			// playthrough brings that one's: the save stays this one.
			if (isMainCharacter(existing)) {
				final Optional<Property> session = sessionID(existingMatch.get());
				final Optional<Property> brought = sessionID(characterProperty.get());
				if (session.isPresent() && brought.isPresent()) {
					Property.update(brought.get(), session.get().obj);
				}
			}
		} else {
			// Plain import: we need a character in the existing save to
			// 'anchor' the imported character to. Just naively try to put the
			// character somewhere near the anchor point.
			final Optional<ObjectPersistencePacket> anchorPoint = findAnchorPoint(mobileObjects);
			if (!anchorPoint.isPresent()) {
				logger.error("Unable to find anchor point in save.%n");
				return false;
			}

			levelName = anchorPoint.get().LevelName;
			location = new Vector3();
			location.x = anchorPoint.get().Location.x + 1;
			location.y = anchorPoint.get().Location.y + 1;
			location.z = anchorPoint.get().Location.z;

			// Need to regenerate the GUID since it seems to be the same for
			// all player characters.
			final UUID newGUID = UUID.randomUUID();
			Property.update(characterProperty.get(), "GUID", newGUID);
			Property.update(characterProperty.get(), "ObjectID", newGUID.toString());
		}

		// Everything else the file brings keeps the IDs it had in the save it
		// came from. Into another playthrough those are unique; into another
		// save of the same one they need not be -- an item handed to someone
		// else since the export, both copies of a character kept -- and each
		// object would exist twice under one ID, which the game drops both of.
		// The file's copy gets a fresh ID and its lists and slots follow it;
		// what the save keeps is left as it is. What names the character itself
		// is left alone too: a companion's ID is the fixed GUID the game knows
		// the companion by, and each ability's Owner is that.
		final Set<String> taken = new HashSet<>();
		for (final Property object : retainedObjects) {
			final String id = ((ObjectPersistencePacket) object.obj).ObjectID;
			if (id != null) {
				taken.add(id.toLowerCase());
			}
		}

		// The save's areas hold objects too: something of theirs sold since the
		// export is in the store's area under the ID the file still has for it.
		final List<String> brought = new ArrayList<>();
		for (final Property object : chrObjects) {
			brought.add(((ObjectPersistencePacket) object.obj).ObjectID);
		}

		taken.addAll(GuidRemap.heldByAreas(editing, brought));

		final Map<String, UUID> remap = new LinkedHashMap<>();
		for (final Property object : chrObjects) {
			final String id = ((ObjectPersistencePacket) object.obj).ObjectID;
			if (object != characterProperty.get() && id != null && taken.contains(id.toLowerCase())) {
				remap.put(id, UUID.randomUUID());
			}
		}

		if (!remap.isEmpty()) {
			logger.info("Gave %d imported objects IDs of their own (%d references).%n"
				, remap.size(), GuidRemap.rewrite(chrObjects, remap));
		}

		final boolean anchored = anchorCharacter(characterProperty.get(), levelName, location);
		if (!anchored) {
			logger.error("Unable to anchor character!%n");
			return false;
		}

		final SimpleProperty simpleObjCount = deserialized.get().getCount();
		final int count = (int) simpleObjCount.obj;
		final int removed = mobileObjects.size() - retainedObjects.size();
		Property.update(simpleObjCount, count - removed + chrObjects.size());

		final List<Property> totalObjects = new ArrayList<>();
		totalObjects.addAll(chrObjects);
		totalObjects.addAll(retainedObjects);

		deserialized.get().setPackets(totalObjects);
		deserialized.get().replace(mobileObjectsFile);

		return true;
	}

	// Finds a character already in the save that the CHR character would
	// duplicate. Main characters match any other main character (there can
	// only be one Player_ object); companions have fixed GUIDs across saves
	// so they match by ObjectID.
	private Optional<Property> findExistingMatch (
		final ObjectPersistencePacket chrPacket
		, final List<Property> mobileObjects) {

		final boolean chrIsMain = isMainCharacter(chrPacket);

		for (final Property property : mobileObjects) {
			final Optional<ObjectPersistencePacket> packet = unwrapProperty(property);
			if (!packet.isPresent()) {
				continue;
			}

			if (chrIsMain) {
				if (isMainCharacter(packet.get())) {
					return Optional.of(property);
				}
			} else if (chrPacket.ObjectID != null
				&& chrPacket.ObjectID.equals(packet.get().ObjectID)) {

				return Optional.of(property);
			}
		}

		return Optional.empty();
	}

	private List<Property> removeCharacterObjects (
		final List<Property> mobileObjects
		, final Property characterProperty
		, final ObjectPersistencePacket character) {

		final List<Property> retained = new ArrayList<>();
		for (final Property property : mobileObjects) {
			if (property == characterProperty) {
				continue;
			}

			if (property.obj instanceof ObjectPersistencePacket) {
				final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
				if (character.ObjectName != null
					&& character.ObjectName.equals(packet.Parent)) {

					continue;
				}
			}

			retained.add(property);
		}

		return retained;
	}

	// The Player component's SessionID, on the main character's packet.
	private static Optional<Property> sessionID (final Property character) {
		if (!(character instanceof ComplexProperty)) {
			return Optional.empty();
		}

		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(components -> EKUtils.findSubComponent(components, "Player"))
			.flatMap(player -> player.<DictionaryProperty>findProperty("Variables"))
			.flatMap(variables -> variables.<Property>findEntry("SessionID"))
			.filter(value -> value.obj instanceof UUID);
	}

	private boolean isMainCharacter (final ObjectPersistencePacket packet) {
		return packet.ObjectName != null && packet.ObjectName.startsWith("Player_");
	}

	boolean anchorCharacter (
		Property chrProperty
		, String areaName
		, Vector3 newLocation) {

		boolean updatedAreaName =
			Property.update(chrProperty, "LevelName", areaName);

		if (!updatedAreaName) {
			return false;
		}

		Optional<Property> locationProperty =
			Property.find(chrProperty, "Location");

		if (!locationProperty.isPresent()) {
			logger.error(
				"Unable to find Location property of character object!%n");
			return false;
		}

		boolean updatedLocation =
			Property.update(locationProperty.get(), "x", newLocation.x);

		updatedLocation = updatedLocation
			&& Property.update(locationProperty.get(), "y", newLocation.y);

		updatedLocation = updatedLocation
			&& Property.update(locationProperty.get(), "z", newLocation.z);

		if (!updatedLocation) {
			logger.error("Unable to locate character's location!%n");
			return false;
		}

		return true;
	}

	private Optional<Property> findCharacter (List<Property> chrObjects) {
		for (Property property : chrObjects) {
			Optional<ObjectPersistencePacket> packet = unwrapProperty(property);

			if (packet.isPresent()
				&& (packet.get().ObjectName.startsWith("Player_")
					|| packet.get().ObjectName.startsWith("Companion_"))) {

				return Optional.of(property);
			}
		}

		return Optional.empty();
	}

	private Optional<ObjectPersistencePacket> findAnchorPoint (
		List<Property> mobileObjects) {

		for (Property property : mobileObjects) {
			Optional<ObjectPersistencePacket> packet = unwrapProperty(property);

			if (packet.isPresent()
				&& packet.get().ObjectName.startsWith("Player_")) {

				return packet;
			}
		}

		return Optional.empty();
	}

	// Real saves contain packets with null ObjectNames as well as the odd
	// property that isn't an ObjectPersistencePacket at all.
	private Optional<ObjectPersistencePacket> unwrapProperty (Property property) {
		if (!(property.obj instanceof ObjectPersistencePacket)) {
			return Optional.empty();
		}

		ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
		if (packet.ObjectName == null) {
			return Optional.empty();
		}

		return Optional.of(packet);
	}
}
