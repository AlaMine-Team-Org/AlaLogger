package day.alacraft.alalogger;

import java.util.List;

/**
 * One screenful of a longer list.
 *
 * <p>Pure arithmetic, kept out of the command module so it can be tested
 * without a game: an off-by-one here shows up as a file nobody can reach, and
 * nothing in chat would say so.
 *
 * @param items  the entries on this page
 * @param number this page, counted from 1
 * @param count  how many pages there are, never less than 1
 */
public record Page<T>(List<T> items, int number, int count) {

    public static <T> Page<T> of(List<T> all, int requested, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("page size must be positive: " + size);
        }

        int count = Math.max(1, (all.size() + size - 1) / size);

        // Clamped rather than refused: the list is read again for every page, so
        // a [next] button printed a moment ago can point past the end once a file
        // is gone. The last page is the useful answer to that click.
        int number = Math.max(1, Math.min(requested, count));
        int from = (number - 1) * size;

        return new Page<>(List.copyOf(all.subList(from, Math.min(all.size(), from + size))), number, count);
    }

    public boolean hasPrevious() {
        return number > 1;
    }

    public boolean hasNext() {
        return number < count;
    }
}
