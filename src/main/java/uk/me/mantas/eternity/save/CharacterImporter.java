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
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.ReferenceTargetProperty;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
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

		List<Property> chrObjects = chrDeserialized.get().getPackets();
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
		boolean keepsPartysBelongings = false;
		final String levelName;
		final Vector3 location;

		if (existingMatch.isPresent()) {
			// Overwrite: the imported character replaces the existing one
			// (and everything it owns) and takes its exact position. Its
			// identity is kept so no GUID regeneration happens.
			final ObjectPersistencePacket existing =
				(ObjectPersistencePacket) existingMatch.get().obj;

			// The stash, the quest items and the crafting materials hang off
			// the main character alone, but they are the party's: the save
			// keeps its own, and what the file's Watcher had in them stays
			// behind in the playthrough it came from.
			final Set<String> partys = new HashSet<>();
			if (isMainCharacter(existing)) {
				if (!movable(existingMatch.get()) || !movable(characterProperty.get())) {
					return false;
				}

				partys.addAll(partysBelongings(existing));
				final Set<String> left = partysBelongings(chrPacket);
				final List<Property> brought = new ArrayList<>();
				for (final Property object : chrObjects) {
					final String id = ((ObjectPersistencePacket) object.obj).ObjectID;
					if (id == null || !left.contains(id.toLowerCase())) {
						brought.add(object);
					}
				}

				chrObjects = brought;
				keepsPartysBelongings = true;
			}

			retainedObjects =
				removeCharacterObjects(mobileObjects, existingMatch.get(), existing, partys);
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

		// Only now: the save's containers name the save's own objects, which
		// keep their IDs, and the remap above is for the file's.
		if (keepsPartysBelongings) {
			keepPartysBelongings(existingMatch.get(), characterProperty.get(), retainedObjects);
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

	// Everything but the character and what they own; what {@code kept} names
	// (lower case) stays.
	private List<Property> removeCharacterObjects (
		final List<Property> mobileObjects
		, final Property characterProperty
		, final ObjectPersistencePacket character
		, final Set<String> kept) {

		final List<Property> retained = new ArrayList<>();
		for (final Property property : mobileObjects) {
			if (property == characterProperty) {
				continue;
			}

			if (property.obj instanceof ObjectPersistencePacket) {
				final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
				if (character.ObjectName != null
					&& character.ObjectName.equals(packet.Parent)
					&& (packet.ObjectID == null || !kept.contains(packet.ObjectID.toLowerCase()))) {

					continue;
				}
			}

			retained.add(property);
		}

		return retained;
	}

	// The party's containers, on the main character. Their money is on
	// PlayerInventory, whose pack is their own.
	private static final List<String> PARTYS =
		Arrays.asList("StashInventory", "QuestInventory", "CraftingInventory");

	// The IDs (lower case) the party's containers on a main character list.
	private static Set<String> partysBelongings (final ObjectPersistencePacket character) {
		final Set<String> ids = new HashSet<>();
		for (final String container : PARTYS) {
			EKUtils.findComponent(character.ComponentPackets, container)
				.map(component -> component.Variables.get("SerializedItemList"))
				.filter(list -> list instanceof CSharpCollection)
				.ifPresent(list -> {
					for (final Iterator<?> it = ((CSharpCollection) list).iterator(); it.hasNext();) {
						ids.add(it.next().toString().toLowerCase());
					}
				});
		}

		return ids;
	}

	// The containers move from one character's packet to another's whole. A
	// packet is written with the reference IDs it was read with, so one that
	// carried a shared reference would bring an ID the other packet may use
	// for something else. None of thirteen saves measured has one
	// (2026-10-27); a save that does is refused rather than written wrong.
	private static boolean movable (final Property character) {
		for (final String container : PARTYS) {
			final Optional<ComplexProperty> component = component(character, container);
			if (!component.isPresent()) {
				logger.error("The main character has no %s.%n", container);
				return false;
			}

			if (sharesReference(component.get())) {
				logger.error("The main character's %s shares a reference; not moving it.%n", container);
				return false;
			}
		}

		return component(character, "PlayerInventory")
			.flatMap(inventory -> inventory.<DictionaryProperty>findProperty("Variables"))
			.flatMap(variables -> variables.<ComplexProperty>findEntry("currencyTotalValue"))
			.isPresent();
	}

	// The save's containers, and what they hold, go to the newcomer; so does
	// the save's money.
	private static void keepPartysBelongings (
		final Property existing, final Property newcomer, final List<Property> retained) {

		final ObjectPersistencePacket newcomerPacket = (ObjectPersistencePacket) newcomer.obj;
		final ObjectPersistencePacket existingPacket = (ObjectPersistencePacket) existing.obj;
		final Set<String> partys = partysBelongings(existingPacket);

		final SingleDimensionalArrayProperty components =
			((ComplexProperty) newcomer).<SingleDimensionalArrayProperty>findProperty("ComponentPackets").get();
		for (final String container : PARTYS) {
			final ComplexProperty saves = component(existing, container).get();
			final ComplexProperty theirs = component(newcomer, container).get();
			replace(components.items, theirs, saves);
			replace(Arrays.asList(newcomerPacket.ComponentPackets), theirs.obj, saves.obj);
		}

		final Property money = currency(existing).get().<Property>findProperty("v").get();
		Property.update(currency(newcomer).get(), "v", money.obj);

		// The camping supplies sit beside the money, an int; a save that
		// records none keeps none.
		final Optional<Property> supplies = playerInventoryVariable(existing, "campingSupplies");
		final Optional<Property> theirSupplies = playerInventoryVariable(newcomer, "campingSupplies");
		if (supplies.isPresent() && theirSupplies.isPresent()) {
			Property.update(theirSupplies.get(), supplies.get().obj);
		}

		for (final Property object : retained) {
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) object.obj;
			if (packet.ObjectID != null && partys.contains(packet.ObjectID.toLowerCase())) {
				Property.update(object, "Parent", newcomerPacket.ObjectName);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void replace (final List items, final Object was, final Object now) {
		final int at = items.indexOf(was);
		if (at >= 0) {
			items.set(at, now);
		}
	}

	private static Optional<ComplexProperty> component (final Property character, final String type) {
		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(components -> EKUtils.findSubComponent(components, type));
	}

	private static Optional<ComplexProperty> currency (final Property character) {
		return component(character, "PlayerInventory")
			.flatMap(inventory -> inventory.<DictionaryProperty>findProperty("Variables"))
			.flatMap(variables -> variables.<ComplexProperty>findEntry("currencyTotalValue"));
	}

	private static Optional<Property> playerInventoryVariable (final Property character, final String name) {
		return component(character, "PlayerInventory")
			.flatMap(inventory -> inventory.<DictionaryProperty>findProperty("Variables"))
			.flatMap(variables -> variables.<Property>findEntry(name));
	}

	private static boolean sharesReference (final Property property) {
		if (property == null) {
			return false;
		}

		if (property instanceof ReferenceTargetProperty
			&& ((ReferenceTargetProperty) property).reference != null) {

			return true;
		}

		if (property instanceof SingleDimensionalArrayProperty) {
			for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
				if (item instanceof Property && sharesReference((Property) item)) {
					return true;
				}
			}
		}

		if (property instanceof MultiDimensionalArrayProperty) {
			for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem item
				: ((MultiDimensionalArrayProperty) property).items) {

				if (item != null && sharesReference(item.value)) {
					return true;
				}
			}
		}

		if (property instanceof CollectionProperty) {
			for (final Property item : ((CollectionProperty) property).items) {
				if (sharesReference(item)) {
					return true;
				}
			}
		}

		if (property instanceof DictionaryProperty) {
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				if (entry != null && (sharesReference(entry.getKey()) || sharesReference(entry.getValue()))) {
					return true;
				}
			}
		}

		if (property instanceof ComplexProperty) {
			for (final Property child : ((ComplexProperty) property).properties) {
				if (sharesReference(child)) {
					return true;
				}
			}
		}

		return false;
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
