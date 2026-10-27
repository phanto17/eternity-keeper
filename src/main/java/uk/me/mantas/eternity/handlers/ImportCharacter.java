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


package uk.me.mantas.eternity.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.WorkingSave;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.save.CharacterImporter.ImportConflict;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

public class ImportCharacter extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ImportCharacter.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		// Second phase of an import: the client already knows which CHR file
		// to use (typically after the user confirmed an overwrite), so no
		// file dialog is needed.
		String confirmedChrPath = null;
		boolean overwrite = false;
		try {
			final JSONObject json = new JSONObject(request);
			if (json.has("chrPath")) {
				confirmedChrPath = json.getString("chrPath");
				overwrite = json.optBoolean("overwrite", false);
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		if (confirmedChrPath != null) {
			final String chrPath = confirmedChrPath;
			final boolean overwriteExisting = overwrite;
			Environment.getInstance().mutationWorker().execute(Answered.to(callback
				, () -> doImport(request, callback, chrPath, overwriteExisting)));
		} else {
			ChrDialog.choose(browser, FileDialogMode.FILE_DIALOG_OPEN, "Choose a character"
				, callback, "NO_SAVE", chrFile -> analyseThenImport(request, callback, chrFile));
		}

		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	// First phase: if the character already exists in the target save, ask
	// the client to confirm the overwrite instead of importing right away.
	private void analyseThenImport (
		final String request
		, final CefQueryCallback callback
		, final String chrFile) {

		try {
			final CharacterImporter importer = new CharacterImporter(request, chrFile);
			final Optional<ImportConflict> conflict = importer.detectConflict();

			if (conflict.isPresent()) {
				final JSONObject confirm = new JSONObject();
				confirm.put("characterName", conflict.get().characterName);
				confirm.put("isMainCharacter", conflict.get().mainCharacter);
				confirm.put("chrPath", chrFile);

				final JSONObject response = new JSONObject();
				response.put("confirmOverwrite", confirm);
				callback.success(response.toString());
				return;
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		} catch (final FileNotFoundException e) {
			logger.error("File not found: %s%n", e.getMessage());
			callback.failure(-1, "Unable to find your save or CHR file.");
			return;
		} catch (final WriteRefusedException e) {
			logger.error("Import refused: %s%n", e.getMessage());
			callback.failure(-1, e.getMessage());
			return;
		} catch (final IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error reading save or CHR file.");
			return;
		}

		doImport(request, callback, chrFile, false);
	}

	private void doImport (
		final String request
		, final CefQueryCallback callback
		, final String chrFile
		, final boolean overwrite) {

		final WorkingSave working = Environment.getInstance().state().workingSave();
		boolean recording = false;
		try {
			final CharacterImporter importer = new CharacterImporter(request, chrFile);

			// An import is a step of the save's history like any Apply, kept
			// against the directory it edits: the private copy, made now if
			// this is the session's first edit.
			final JSONObject json = new JSONObject(request);
			final File save = working.forEditing(
				new File(json.getString("oldSave")), json.getBoolean("savedYet"));
			working.history().begin(save);
			recording = true;

			final boolean success = overwrite
				? importer.overwriteCharacter()
				: importer.importCharacter();

			if (success) {
				final long step = working.history().commit();
				recording = false;

				final SavedGameOpener opener = new SavedGameOpener(
					importer.saveFile().getAbsolutePath()
					, callback);

				if (step > 0) {
					opener.with("historyStep", step);
				}

				try {
					opener.reopen();
				} catch (final SavedGameOpener.Unreadable e) {
					logger.error("Reading %s back after the import failed: %s%n", save.getAbsolutePath(), e.getMessage());
					takeBack(working, save, step);
					callback.failure(-1, e.takenBack());
				} catch (final RuntimeException | StackOverflowError e) {
					takeBack(working, save, step);
					throw e;
				}
			} else {
				callback.failure(-1, "Character import failed.");
			}
		} catch (JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
		} catch (FileNotFoundException e) {
			logger.error("File not found: %s%n", e.getMessage());
			callback.failure(-1, "Unable to find your save or CHR file.");
		} catch (WriteRefusedException e) {
			// The save or the character file could be read only in part, or
			// the import would have left the save contradicting itself; the
			// message says which, and nothing was imported.
			logger.error("Import refused: %s%n", e.getMessage());
			callback.failure(-1, e.getMessage());
		} catch (IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error modifying temporary MobileObjects.save");
		} finally {
			// Refused or failed: whatever it wrote goes back.
			if (recording) {
				working.history().abort();
			}
		}
	}

	// Written, and then not handed back: the page is told the import failed
	// and goes on showing the save as it was, so the save goes back to that
	// too (as SaveMutationHandler's).
	private static void takeBack (final WorkingSave working, final File save, final long step) {
		if (step > 0) {
			try {
				working.history().undo(save, step);
			} catch (final IOException undoing) {
				logger.error("The import could not be taken back either: %s%n", undoing.getMessage());
			}
		}
	}
}
