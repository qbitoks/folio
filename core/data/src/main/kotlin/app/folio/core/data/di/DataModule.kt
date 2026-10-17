package app.folio.core.data.di

import android.content.Context
import androidx.room.Room
import app.folio.core.data.db.FolioDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FolioDatabase =
        Room.databaseBuilder(context, FolioDatabase::class.java, "folio.db").fallbackToDestructiveMigration(dropAllTables = true).build()
}
