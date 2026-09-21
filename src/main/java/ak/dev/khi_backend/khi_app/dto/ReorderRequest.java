package ak.dev.khi_backend.khi_app.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * ReorderRequest — bulk ordering payload for list-level drag & drop.
 *
 * `orderedIds` carries every record id in the desired display order.
 * The service assigns `sortOrder = index` to each id; ids not listed keep
 * their previous position and sort after the listed ones (nulls last).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReorderRequest {

    private List<Long> orderedIds;
}
