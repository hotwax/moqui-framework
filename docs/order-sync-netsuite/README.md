# Order sync to NetSuite

Two documents.

`what-order-create-does-today.md` lists everything the current CSV process does when
it creates a sales order in NetSuite, in the order it happens, and what each piece
means for the REST path.

`how-the-rest-path-works.md` describes the new path: the picker views, the four
services, what the order carries, what is still missing, and what was proved on the
sandbox.

`record/` holds the same two documents with the file and line for every row, the
field ids, the sandbox evidence, and the dates. Read it when you need to know where
a rule lives or who proved what.

The plan, as one page, is the GitHub issue hotwax/mantle-netsuite-connector#400. It lists the
business process, the three parts of the build, the acceptance checks and the four pull
requests, with a dated status at the end.
