# stand

What the scenarios in the parent package share about the stand they run on: the cluster and the Consul installed in it
(`Cluster`), the service under test and the token it presents (`TestService`, `ProjectedToken`), the ACL objects a
scenario builds around itself (`ConsulAcl`, `ConsulClient`, `SigningKey`, `ClusterSigningKey`), and the dump printed
when a scenario fails (`StandDump`). A scenario is then only what differs: the login way, the ACL objects it needs, and
what it asserts.

The Kubernetes and Consul objects are created from here rather than declared in a helm chart because a scenario
decides them while it runs: its own namespace and environment per login way, a signing key generated for the run, and
the projected token only where the way reads it. One scenario also has to bring the pod up before the ACL objects it
later moves to exist, which no install step outside the test can arrange. Every scenario removes what it created, so
all of them share one cluster and one Consul.
