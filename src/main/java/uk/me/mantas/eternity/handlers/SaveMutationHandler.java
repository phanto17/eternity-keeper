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
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.EditHistory;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.IOException;

/**
 * A handler that edits the working save and hands the reopened save back.
 *
 * <p>Every one of them takes {@code {oldSave, savedYet, ...}} and does the same
 * thing around its edit: runs on the mutation worker (invariant 9 — two edits
 * can never write the same file at once), asks {@code WorkingSave} which copy
 * of the save to edit, refuses one that is not there, records the edit as a
 * step of the save's {@link EditHistory} (what it replaces is kept, so the page
 * can undo it; a refused or failed edit puts back whatever it wrote), and
 * reopens the save through {@link SavedGameOpener} so the UI gets exactly what
 * opening it would give, with the step's id as {@code historyStep}.
 * Subclasses supply only {@link #mutate}.
 *
 * <p>These were seven copies of the same fifty lines, kept consistent only by
 * every copy remembering to do it.
 */
public abstract class SaveMutationHandler extends CefMessageRouterHandlerAdapter {
	private final Logger logger = Logger.getLogger(getClass());

	/**
	 * Makes the edit.
	 *
	 * @param save    the live working copy of the save
	 * @param request the whole request, for whatever this handler reads from it
	 * @return {@code null} on success, or what to tell the user when the edit
	 *         was refused
	 * @throws IOException when the save could not be read or written; the
	 *         message reaches the user. A {@link WriteRefusedException} -- a
	 *         save that could be read only in part, or an edit that would have
	 *         left it contradicting itself -- reaches them as it stands.
	 */
	protected abstract String mutate (File save, JSONObject request) throws IOException;

	/**
	 * Whether the edit is a step of the save's history, to be undone. Only
	 * undoing and redoing are not: they move along the history rather than
	 * adding to it.
	 */
	protected boolean recorded () {
		return true;
	}

	@Override
	public final boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(Answered.to(callback, () -> handle(request, callback)));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void handle (final String request, final CefQueryCallback callback) {
		final JSONObject json;
		final File opened;
		final boolean savedYet;

		try {
			json = new JSONObject(request);
			opened = new File(json.getString("oldSave"));
			savedYet = json.getBoolean("savedYet");
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		final EditHistory history = Environment.getInstance().state().workingSave().history();
		File save = opened;
		long step = 0;
		boolean recording = false;
		try {
			// Never the directory the list unpacked: an edit the user goes on
			// to discard must not be there when they open the save again.
			save = Environment.getInstance().state().workingSave().forEditing(opened, savedYet);
			if (!save.exists()) {
				callback.failure(-1, "Unable to find your save file.");
				return;
			}

			// What the edit replaces is kept, so the page can undo it.
			if (recorded()) {
				history.begin(save);
				recording = true;
			}

			final String problem = mutate(save, json);
			if (problem != null) {
				callback.failure(-1, problem);
				return;
			}

			if (recording) {
				step = history.commit();
				recording = false;
			}
		} catch (final JSONException e) {
			logger.error("Error reading request %s: %s%n", request, e.getMessage());
			callback.failure(-1, "Error parsing JSON request.");
			return;
		} catch (final WriteRefusedException e) {
			// A save read only in part, or an edit that would have left it
			// contradicting itself: nothing was written, so "could not write"
			// would blame the wrong step. The refusal says what happened.
			logger.error("Editing %s refused: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, e.getMessage());
			return;
		} catch (final IOException e) {
			logger.error("Editing %s failed: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, "Could not write the save: " + e.getMessage());
			return;
		} finally {
			// Refused, or failed part of the way: whatever it wrote goes back.
			if (recording) {
				history.abort();
			}
		}

		final SavedGameOpener opener = new SavedGameOpener(save.getAbsolutePath(), callback);
		if (step > 0) {
			opener.with("historyStep", step);
		}

		try {
			opener.reopen();
		} catch (final SavedGameOpener.Unreadable e) {
			logger.error("Reading %s back after editing it failed: %s%n", save.getAbsolutePath(), e.getMessage());
			takeBack(history, save, step);
			callback.failure(-1, e.takenBack());
		} catch (final RuntimeException | StackOverflowError e) {
			takeBack(history, save, step);
			throw e;
		}
	}

	// Written, and then not readable back. The page is told the edit was not
	// made and goes on showing the save as it was, so the save goes back to
	// that too: its next edit would otherwise be worked out against a save the
	// page has never seen.
	private void takeBack (final EditHistory history, final File save, final long step) {
		if (step > 0) {
			try {
				history.undo(save, step);
			} catch (final IOException undoing) {
				logger.error("The edit could not be taken back either: %s%n", undoing.getMessage());
			}
		}
	}
}
