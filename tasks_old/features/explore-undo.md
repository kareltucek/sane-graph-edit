# Undo in explore mode

Now: nothing done in explore mode is an undo step, and leaving it
puts the positions back as they were before it (`Graph.beforeExplore`).

Probably wanted instead: changes in explore mode are undo steps as
usual; leaving it rolls back all of them, to the undo step before
explore was switched on, unless the user says to keep them (an
`:apply`-like command).
