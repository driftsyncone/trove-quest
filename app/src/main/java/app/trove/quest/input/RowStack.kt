package app.trove.quest.input

/**
 * The open rows in the order they were opened (docs/control-scheme.md section 3, layer 4): B closes
 * the newest. The rows themselves are state of their panels (a screen's View / Stream / Markers, the
 * wall's Sort and account), which also change by their own buttons; so the stack is rebuilt each
 * frame from what is open now, keeping the order of the ones that stay and putting new ones last.
 */
class RowStack {
  private var order = emptyList<Row>()

  /** [open]: every row that is open right now (any order). Returns them oldest first. */
  fun update(open: Collection<Row>): List<Row> {
    val kept = order.filter { it in open }
    order = kept + open.filter { it !in kept }
    return order
  }
}
