# Order sync to NetSuite

Four documents.

`what-order-create-does-today.md` lists everything the current CSV process does when
it creates a sales order in NetSuite, in the order it happens, and what each piece
means for the REST path.

`the-rules-the-manager-writes.md` is the fulfillment manager's story: twelve wishes, in her
words, and under each one the rows of data that fulfil it, until the rule set is complete.

`test-scenarios.md` lists the rules a business user would program, what each must do, and
what happened when all fourteen ran against a real hour of production orders.

`how-the-rest-path-works.md` describes the new path: the picker views, the four
services, what the order carries, what is still missing, and what was proved on the
sandbox.

`record/` holds the same documents with the file and line for every row, the
field ids, the sandbox evidence, and the dates. Read it when you need to know where
a rule lives or who proved what.

The plan, as one page, is the GitHub issue hotwax/mantle-netsuite-connector#400. It lists the
business process, the three parts of the build, the acceptance checks and the four pull
requests, with a dated status and links to these documents at the end.
