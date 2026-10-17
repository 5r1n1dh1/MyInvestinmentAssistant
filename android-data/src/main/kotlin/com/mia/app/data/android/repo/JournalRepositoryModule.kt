package com.mia.app.data.android.repo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The journal repository's binding, in its own module.
 *
 * ### Why this is not a second `@Binds` block inside `RepositoryModule`
 * `RepositoryModule` is the module every repository is bound in, and it is **not** this wave's
 * file to edit: another workstream owns it, and a conflicting edit costs the whole branch. A module
 * per addition is a legitimate Hilt pattern and it compiles to exactly the same graph, so the
 * trade is: one more file now, and a merge conflict avoided. Whichever repository lands last here
 * can fold this into `RepositoryModule` and delete this file — the binding is identical either way.
 *
 * `@Binds` rather than `@Provides` so the constructor keeps stating the dependencies: a reviewer
 * asking "what does the journal read?" gets the answer from the class, not from this file.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class JournalRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindJournalRepository(impl: DefaultJournalRepository): JournalRepository
}